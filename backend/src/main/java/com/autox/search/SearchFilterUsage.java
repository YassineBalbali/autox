package com.autox.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts which filters public searches use, for the admin dashboard: the filter's name, never its
 * value. A search for diesels under 15 000 € counts once for {@code fuelType} and once for
 * {@code price}, and nothing records that it was diesel or 15 000.
 *
 * <p>Best-effort and apart from the search: its own transaction, and a failure here is logged and
 * costs the visitor nothing.
 */
@Service
public class SearchFilterUsage {

    private static final Logger log = LoggerFactory.getLogger(SearchFilterUsage.class);

    /** Every search, so that a filter's share of them can be given. */
    public static final String ALL = "_all";

    private final NamedParameterJdbcTemplate jdbc;

    public SearchFilterUsage(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ListingSearchCriteria criteria) {
        List<String> names = new ArrayList<>(filtersOf(criteria));
        names.add(ALL);
        try {
            jdbc.update("""
                    INSERT INTO search_filter_usage (hour, filter, searches)
                    SELECT date_trunc('hour', now()), name, 1 FROM unnest(CAST(:names AS text[])) AS name
                    ON CONFLICT (hour, filter) DO UPDATE SET searches = search_filter_usage.searches + 1
                    """, new MapSqlParameterSource("names", "{" + String.join(",", names) + "}"));
        } catch (RuntimeException failure) {
            log.warn("Search filter usage could not be counted: {}", failure.getClass().getSimpleName());
        }
    }

    /** Housekeeping: two quarters, the longest period and the one before it. */
    @Transactional
    public int purgeBefore(Instant before) {
        return jdbc.update("DELETE FROM search_filter_usage WHERE hour < :before",
                new MapSqlParameterSource("before", java.sql.Timestamp.from(before)));
    }

    /**
     * The filters a search uses, by name. A minimum and a maximum are one filter, and a make given by
     * id or by name is one too. The defaults are not filters: a sale, and damaged vehicles left out.
     */
    public static List<String> filtersOf(ListingSearchCriteria c) {
        List<String> names = new ArrayList<>();
        add(names, "text", hasText(c.q()));
        add(names, "make", c.makeId() != null || hasText(c.make()));
        add(names, "model", c.modelId() != null || hasText(c.model()));
        add(names, "year", c.yearFrom() != null || c.yearTo() != null);
        add(names, "price", c.priceMin() != null || c.priceMax() != null);
        add(names, "mileage", c.mileageMin() != null || c.mileageMax() != null);
        add(names, "fuelType", notEmpty(c.fuelType()));
        add(names, "gearbox", notEmpty(c.gearbox()));
        add(names, "bodyType", notEmpty(c.bodyType()));
        add(names, "drivetrain", notEmpty(c.drivetrain()));
        add(names, "condition", notEmpty(c.condition()));
        add(names, "damage", c.damage() != null && c.damage() != DamageFilter.UNDAMAGED);
        add(names, "location", hasText(c.location()) || c.hasBoundingBox());
        add(names, "goodDeals", notEmpty(c.priceLevel()));
        add(names, "leasing", c.offer() == com.autox.listing.OfferType.LEASING);
        add(names, "leasingType", notEmpty(c.leasingType()));
        add(names, "monthly", c.monthlyMin() != null || c.monthlyMax() != null);
        add(names, "duration", c.durationMin() != null || c.durationMax() != null);
        add(names, "mileageAllowance", c.mileageAllowanceMin() != null || c.mileageAllowanceMax() != null);
        add(names, "seller", c.sellerId() != null);
        return names;
    }

    private static void add(List<String> names, String name, boolean used) {
        if (used) {
            names.add(name);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean notEmpty(List<?> values) {
        return values != null && !values.isEmpty();
    }
}
