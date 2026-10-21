package com.autox.search;

import com.autox.search.dto.PriceIndicator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The price indicator: how a listing's price compares with what comparable cars are listed
 * for.
 *
 * <h2>The rule</h2>
 *
 * <p>A listing's <b>comparables</b> are the other live listings of
 * <ul>
 *   <li>the same catalogue make and model (a listing identified only by free text has no
 *       reliable model to compare on, and gets no indicator),</li>
 *   <li>the same fuel and a power within 15 % ({@link #POWER_SPAN}) of the listing's — in effect
 *       the same engine, since a car is valued by its version, not its model: a Model 3
 *       Performance is not a dear Model 3 Propulsion. A listing that states no power gets no
 *       indicator,</li>
 *   <li>a first registration within {@value #YEAR_SPAN} years either side,</li>
 *   <li>the same {@linkplain #BAND mileage band}: under 30 000 km, 30–60 000, 60–100 000,
 *       100–150 000, 150 000 and over,</li>
 *   <li>the same currency, and not new: a new car's price is the maker's list price less a
 *       dealer's discount, not a market price, so new cars are neither judged nor used as a
 *       yardstick.</li>
 * </ul>
 *
 * <p>With at least {@value #MIN_COMPARABLES} comparables, the listing's price is set against
 * their <b>median</b> — not the mean, which one mistyped price would drag — and called a good
 * deal at {@value #DEAL_MARGIN} or more below it, above the market at that much above it, and
 * the market price in between. With fewer, nothing is said.
 *
 * <h2>Computed when read</h2>
 *
 * <p>The comparison is made in the query that reads the listings, never stored. A stored
 * figure would have to be refreshed whenever any comparable listing changes price, sells,
 * expires, is withdrawn or is rejected by a moderator — miss one path and the badge is
 * quietly wrong. Computed on read it cannot be stale. The cost is one index range scan per
 * listing on {@code ix_listing_live_market} (model, year), which for a page of results is
 * small. For the {@code priceLevel} filter it is one such scan per candidate listing, which
 * is fine at this catalogue's size; at a national marketplace's it would become a table
 * maintained on write, and the SQL here is what would fill it.
 *
 * <p>The rule exists once, as SQL, below. The results page, the listing page and the filter
 * all use this same fragment, so they cannot disagree about a listing.
 */
@Component
public class MarketPrice {

    /** Fewer comparables than this and the indicator is not shown. */
    public static final int MIN_COMPARABLES = 5;

    /** How many years either side of the listing's first registration a comparable may be. */
    public static final int YEAR_SPAN = 2;

    /**
     * How far a comparable's power may be from the listing's, as a fraction of the listing's.
     * Wide enough for the same engine across a facelift (a 130 that became a 136), narrow
     * enough to keep apart the engines a model is offered with, which are rarely closer than
     * a fifth.
     */
    public static final double POWER_SPAN = 0.15;

    /** How far from the median, as a fraction of it, before a price is no longer "the market". */
    public static final double DEAL_MARGIN = 0.10;

    /** The mileage band of a listing aliased {@code %1$s}, as an integer from 0 to 4. */
    private static final String BAND = """
            CASE WHEN %1$s.mileage_km < 30000 THEN 0
                 WHEN %1$s.mileage_km < 60000 THEN 1
                 WHEN %1$s.mileage_km < 100000 THEN 2
                 WHEN %1$s.mileage_km < 150000 THEN 3
                 ELSE 4 END""";

    /**
     * The comparables of the listing aliased {@code l}, as columns {@code mp.comparables} and
     * {@code mp.median}. Joined to nothing for a listing that cannot have an indicator, so
     * those columns are then null.
     */
    static final String LATERAL = """
             LEFT JOIN LATERAL (
                SELECT count(*) AS comparables,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY c.price_amount) AS median
                  FROM listing c
                 WHERE c.model_id = l.model_id
                   AND c.id <> l.id
                   AND c.price_currency_code = l.price_currency_code
                   AND c.vehicle_condition <> 'NEW'
                   AND c.fuel_type = l.fuel_type
                   AND c.power_hp BETWEEN l.power_hp * (1 - %5$s) AND l.power_hp * (1 + %5$s)
                   AND c.first_registration_year BETWEEN l.first_registration_year - %1$d
                                                     AND l.first_registration_year + %1$d
                   AND %2$s = %3$s
                   AND %4$s
             ) mp ON l.model_id IS NOT NULL AND l.power_hp IS NOT NULL AND l.vehicle_condition <> 'NEW'
            """.formatted(YEAR_SPAN, BAND.formatted("c"), BAND.formatted("l"),
                    ListingSearchRepository.visible("c"), POWER_SPAN);

    /** The level of the listing aliased {@code l}, or null; needs {@link #LATERAL}. */
    static final String LEVEL = """
            CASE WHEN mp.comparables >= %1$d THEN
                CASE WHEN l.price_amount <= (1 - %2$s) * mp.median THEN 'GOOD_DEAL'
                     WHEN l.price_amount >= (1 + %2$s) * mp.median THEN 'ABOVE_MARKET'
                     ELSE 'MARKET' END
            END""".formatted(MIN_COMPARABLES, DEAL_MARGIN);

    private final NamedParameterJdbcTemplate jdbc;

    public MarketPrice(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The indicator for each of the given listings that has one; a listing without one is
     * absent from the map. One query for the lot.
     */
    public Map<UUID, PriceIndicator> indicators(List<UUID> listingIds) {
        if (listingIds.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT l.id, mp.comparables, mp.median, " + LEVEL + " AS price_level"
                + " FROM listing l" + LATERAL
                + " WHERE l.id IN (:ids)";

        Map<UUID, PriceIndicator> indicators = new HashMap<>();
        jdbc.query(sql, new MapSqlParameterSource("ids", listingIds), rs -> {
            String level = rs.getString("price_level");
            if (level != null) {
                indicators.put(rs.getObject("id", UUID.class), new PriceIndicator(
                        PriceLevel.valueOf(level),
                        Math.round(rs.getDouble("median")),
                        rs.getInt("comparables")));
            }
        });
        return indicators;
    }
}
