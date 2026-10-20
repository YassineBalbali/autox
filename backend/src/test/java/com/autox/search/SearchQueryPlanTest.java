package com.autox.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.autox.support.AbstractIntegrationTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Checks that search actually uses the indexes it was designed around.
 *
 * <p>A correctness test passes on ten rows whatever the plan is. This one loads enough rows
 * that the planner makes the choice it would make in production, then reads the plan. Without
 * it, "we have an index for that" is a belief rather than a fact.
 *
 * <p>The assertions are deliberately coarse — no sequential scan on the hot paths — and the
 * plans are printed so the index actually chosen is visible in the build output.
 */
class SearchQueryPlanTest extends AbstractIntegrationTest {

    private static final int LISTINGS = 20_000;

    @Autowired ListingSearchService searchService;

    @Test
    @DisplayName("every sort and the common filters are served by an index, not a scan")
    void searchPlansUseIndexes() {
        seedListings();

        Map<String, String> plans = new LinkedHashMap<>();
        ListingSearchCriteria empty = criteria(null, null, null, null);

        for (SearchSort sort : SearchSort.values()) {
            plans.put("sort=" + sort + " (first page)", searchService.explainSearch(empty, sort, null, 24));
        }

        // Second page: the keyset predicate is what has to be index-driven, not just the sort.
        for (SearchSort sort : SearchSort.values()) {
            String cursor = firstCursor(sort);
            plans.put("sort=" + sort + " (second page)", searchService.explainSearch(empty, sort, cursor, 24));
        }

        plans.put("filter: price range, newest",
                searchService.explainSearch(criteria(null, 1_000_000L, 2_000_000L, null), SearchSort.NEWEST, null, 24));
        plans.put("filter: make name, newest",
                searchService.explainSearch(criteria("BMW", null, null, null), SearchSort.NEWEST, null, 24));
        plans.put("filter: mileage ceiling, mileage asc",
                searchService.explainSearch(criteria(null, null, null, 100_000), SearchSort.MILEAGE_ASC, null, 24));

        plans.forEach((label, plan) -> {
            System.out.println("=== " + label);
            System.out.println(plan);
        });

        // The four sorts, first and second page, must all avoid a full scan.
        for (SearchSort sort : SearchSort.values()) {
            assertThat(plans.get("sort=" + sort + " (first page)"))
                    .as("first page of %s must not sequentially scan listing", sort)
                    .doesNotContain("Seq Scan on listing");
            assertThat(plans.get("sort=" + sort + " (second page)"))
                    .as("second page of %s must not sequentially scan listing", sort)
                    .doesNotContain("Seq Scan on listing");
        }

        // The default ordering is the one almost every visitor hits.
        assertThat(plans.get("sort=NEWEST (first page)")).contains("ix_listing_live_published");
        assertThat(plans.get("sort=NEWEST (second page)")).contains("ix_listing_live_published");

        // Price and mileage sorts need their own keyset indexes; see V9.
        assertThat(plans.get("sort=PRICE_ASC (second page)")).contains("ix_listing_live_price");
        assertThat(plans.get("sort=MILEAGE_ASC (second page)")).contains("ix_listing_live_mileage");

        // A sort node means the whole matching set was materialised and ordered, which is what
        // keyset pagination exists to avoid.
        assertThat(plans.get("sort=NEWEST (first page)")).doesNotContain("Sort Method");
        assertThat(plans.get("sort=PRICE_ASC (first page)")).doesNotContain("Sort Method");
        assertThat(plans.get("sort=MILEAGE_ASC (first page)")).doesNotContain("Sort Method");
    }

    private String firstCursor(SearchSort sort) {
        CursorPage<?> page = searchService.search(criteria(null, null, null, null), sort, null, 24);
        assertThat(page.hasMore()).as("20k rows should not fit on one page").isTrue();
        return page.nextCursor();
    }

    private static ListingSearchCriteria criteria(String make, Long priceMin, Long priceMax, Integer mileageMax) {
        return new ListingSearchCriteria(null, null, make, null, null, null,
                priceMin, priceMax, null, null, mileageMax,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, null, List.of());
    }

    /**
     * Twenty thousand published listings, spread across makes, prices, mileages and
     * publication dates so that no single filter is trivially selective.
     */
    private void seedListings() {
        UUID sellerId = createUser(uniqueEmail("plan-seller"), com.autox.user.UserRole.PROFESSIONNEL,
                com.autox.user.UserStatus.ACTIVE).getId();

        jdbcTemplate.update("""
                INSERT INTO listing (id, seller_id, status, moderation_status, title,
                                     price_amount, price_currency_code, country_code,
                                     postal_code, city, make_text, model_text,
                                     first_registration_year, mileage_km, fuel_type, gearbox,
                                     body_type, vehicle_condition, published_at, expires_at,
                                     created_at, updated_at)
                SELECT gen_random_uuid(), ?, 'ACTIVE', 'NOT_REVIEWED', 'Listing ' || g,
                       500000 + ((g * 37) % 4000000), 'EUR', 'FR',
                       '7501' || (g % 10), 'Paris',
                       (ARRAY['BMW','Peugeot','Renault','Volkswagen','Audi'])[1 + (g % 5)],
                       (ARRAY['3 Series','208','Clio','Golf','A4'])[1 + (g % 5)],
                       (2005 + (g % 20))::smallint, (g * 53) % 300000,
                       (ARRAY['DIESEL','PETROL','ELECTRIC'])[1 + (g % 3)],
                       (ARRAY['MANUAL','AUTOMATIC'])[1 + (g % 2)],
                       'SEDAN', 'USED',
                       now() - (g || ' minutes')::interval,
                       now() + interval '60 days',
                       now(), now()
                  FROM generate_series(1, ?) g
                """, sellerId, LISTINGS);

        jdbcTemplate.execute("ANALYZE listing");
    }
}
