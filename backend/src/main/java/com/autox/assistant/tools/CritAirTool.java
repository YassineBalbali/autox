package com.autox.assistant.tools;

import com.autox.catalog.FuelType;
import com.autox.listing.CritAir;
import java.time.Year;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The {@code crit_air} tool: the class a car would have, from the one place the rule is written,
 * the SQL function {@code crit_air_class} (V30, docs/crit-air.md). A listing's class is its
 * stored column; a described car goes through the same function. There is no copy of the table here.
 *
 * <p>When the date does not settle the class, the answer says why rather than guessing: a year
 * whose months fall in different classes needs the month, and a date inside a change of Euro
 * standard, or a fuel the rule does not cover, has no class at all.
 */
@Component
public class CritAirTool {

    public static final int MIN_YEAR = 1950;

    private final ListingFactsReader reader;
    private final JdbcTemplate jdbc;

    public CritAirTool(ListingFactsReader reader, JdbcTemplate jdbc) {
        this.reader = reader;
        this.jdbc = jdbc;
    }

    public ToolResult<Estimate> forListing(UUID listingId) {
        Optional<ListingFacts> found = reader.find(listingId);
        if (found.isEmpty()) {
            return ToolResult.refusal(ToolResult.Reason.LISTING_UNAVAILABLE, listingId);
        }
        ListingFacts listing = found.get();
        if (listing.critAir() != null) {
            return ToolResult.answer(new Estimate(listing.critAir(), null));
        }
        return ToolResult.answer(new Estimate(null, why(listing.fuelType(), listing.year(), listing.month())));
    }

    /** A car that is not a listing. {@code month} may be null. */
    public ToolResult<Estimate> forCar(FuelType fuel, int year, Integer month) {
        if (year < MIN_YEAR || year > Year.now().getValue() + 1 || (month != null && (month < 1 || month > 12))) {
            throw new IllegalArgumentException("Date out of bounds: " + year + "/" + month);
        }
        Short code = classOf(fuel.name(), year, month);
        return ToolResult.answer(code != null
                ? new Estimate(CritAir.fromCode(code), null)
                : new Estimate(null, why(fuel.name(), year, month)));
    }

    private Short classOf(String fuel, int year, Integer month) {
        return jdbc.queryForObject("SELECT public.crit_air_class(?, ?, ?)", Short.class, fuel, year, month);
    }

    /** Called only when the function gave no class for this date. */
    private Unsettled why(String fuel, int year, Integer month) {
        if (month != null) {
            return Unsettled.NOT_SETTLED_BY_DATE;
        }
        List<Short> byMonth = jdbc.queryForList(
                "SELECT public.crit_air_class(?, ?, m) FROM generate_series(1, 12) AS m ORDER BY m",
                Short.class, fuel, year);
        Set<Short> classes = new HashSet<>(byMonth);
        // Some months settle it and the year as a whole does not: the month would.
        return classes.stream().anyMatch(java.util.Objects::nonNull) ? Unsettled.NEEDS_MONTH : Unsettled.NOT_SETTLED_BY_DATE;
    }

    /** @param critAir null exactly when {@code unsettled} says why */
    public record Estimate(CritAir critAir, Unsettled unsettled) {
    }

    public enum Unsettled {
        /** The year alone spans two classes; with the month of first registration there would be one. */
        NEEDS_MONTH,
        /** Inside a change of Euro standard, or a fuel the rule does not cover: the date cannot settle it. */
        NOT_SETTLED_BY_DATE
    }
}
