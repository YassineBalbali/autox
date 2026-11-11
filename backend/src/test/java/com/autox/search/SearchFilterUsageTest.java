package com.autox.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.autox.catalog.FuelType;
import com.autox.listing.LeasingType;
import com.autox.listing.OfferType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A search's filters, by name, as the dashboard counts them. */
class SearchFilterUsageTest {

    private static ListingSearchCriteria criteria(UUID makeId, String make, Long priceMin, Long priceMax, String q,
                                                  List<FuelType> fuel, DamageFilter damage, OfferType offer,
                                                  List<LeasingType> leasingType, Long monthlyMax) {
        return new ListingSearchCriteria(makeId, null, make, null, null, null, priceMin, priceMax, "EUR", null, null,
                fuel, null, null, null, null, null, null, null, null, null, null, q, null, damage, offer,
                leasingType, null, monthlyMax, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("nothing set is no filter, and the defaults are not filters either")
    void none() {
        assertThat(SearchFilterUsage.filtersOf(criteria(null, null, null, null, null, null, null, null, null, null)))
                .isEmpty();
        assertThat(SearchFilterUsage.filtersOf(criteria(null, " ", null, null, "  ", List.of(), DamageFilter.UNDAMAGED,
                OfferType.SALE, List.of(), null))).isEmpty();
    }

    @Test
    @DisplayName("a range is one filter, a make by id or by name is one, and the currency is not one")
    void namesNotValues() {
        assertThat(SearchFilterUsage.filtersOf(criteria(UUID.randomUUID(), "Audi", 100L, 200L, "golf gti",
                List.of(FuelType.DIESEL), DamageFilter.ALL, OfferType.LEASING, List.of(LeasingType.LOA), 30_000L)))
                .containsExactly("text", "make", "price", "fuelType", "damage", "leasing", "leasingType", "monthly");
    }
}
