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
 * <p>Each comparable's price is first <b>brought to the listing's age and mileage</b>: a
 * comparable a year older is worth what it is asking plus a year's depreciation, one with
 * 10 000 km more is worth its price plus that much wear. Without this, the ±2 year window
 * compares a 2024 car with 2022 ones, and a fairly priced car at either end of the window
 * comes out a good deal or above the market only for its age. The assumption is
 * 10 % a year ({@link #YEARLY_DEPRECIATION}) and 2 % per 10 000 km
 * ({@link #DEPRECIATION_PER_10K_KM}); see {@code docs/price-indicator.md} for where those come
 * from and their limits.
 *
 * <p>With at least {@value #MIN_COMPARABLES} comparables, the listing's price is set against
 * the <b>median</b> of the adjusted prices — not the mean, which one mistyped price would
 * drag — and called a good deal at {@value #DEAL_MARGIN} or more below it, above the market at that much above it, and
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

    /**
     * The value a used car loses in a year, as a fraction of its value at the start of that
     * year. One rate for every fuel and every model: a rule of thumb, not fitted to data.
     */
    public static final double YEARLY_DEPRECIATION = 0.10;

    /** The value a used car loses per 10 000 km driven, as a fraction of its value. */
    public static final double DEPRECIATION_PER_10K_KM = 0.02;

    /** How far from the median, as a fraction of it, before a price is no longer "the market". */
    public static final double DEAL_MARGIN = 0.10;

    /**
     * Where one mileage band ends and the next begins, in km: under 30 000, 30–60 000,
     * 60–100 000, 100–150 000, and 150 000 and over.
     */
    private static final int[] BAND_LIMITS = {30_000, 60_000, 100_000, 150_000};

    /** The mileage band of a listing aliased {@code %1$s}, as an integer from 0 to 4. */
    private static final String BAND = bandSql();

    private static String bandSql() {
        StringBuilder sql = new StringBuilder("CASE");
        for (int band = 0; band < BAND_LIMITS.length; band++) {
            sql.append(" WHEN %1$s.mileage_km < ").append(BAND_LIMITS[band]).append(" THEN ").append(band);
        }
        return sql.append(" ELSE ").append(BAND_LIMITS.length).append(" END").toString();
    }

    /** A mileage band, from {@code fromKm} up to but not including {@code toKm}; null for the last. */
    public record MileageBand(int fromKm, Integer toKm) {
    }

    /** The mileage band a mileage falls in: the same bands as the SQL above. */
    public static MileageBand bandOf(int mileageKm) {
        int from = 0;
        for (int limit : BAND_LIMITS) {
            if (mileageKm < limit) {
                return new MileageBand(from, limit);
            }
            from = limit;
        }
        return new MileageBand(from, null);
    }

    /**
     * The first registration of a listing aliased {@code %1$s}, in years with the month as a
     * fraction. A listing that gives no month is taken as registered mid-year.
     */
    private static final String REGISTERED = """
            (%1$s.first_registration_year + (coalesce(%1$s.first_registration_month, 7) - 1) / 12.0)""";

    /**
     * The price of the comparable {@code c} brought to the age and mileage of the listing
     * {@code l}. A comparable registered earlier, or driven more, is worth more at the
     * listing's age and mileage than it is asking, and the reverse.
     */
    private static final String ADJUSTED_PRICE = """
            c.price_amount
                * power(1 / (1 - %1$s), %3$s - %4$s)
                * power(1 - %2$s, (l.mileage_km - c.mileage_km) / 10000.0)""".formatted(
            YEARLY_DEPRECIATION, DEPRECIATION_PER_10K_KM, REGISTERED.formatted("l"), REGISTERED.formatted("c"));

    /**
     * Whether the listing {@code c} is a comparable of the listing {@code l}: the rule above,
     * as a predicate. The price indicator always uses all of it. La Cote can loosen it when a
     * car has too few comparables, and says so whenever it does.
     *
     * @param yearSpan   years either side of {@code l}'s first registration
     * @param sameEngine the same fuel and a power within {@link #POWER_SPAN}
     * @param sameBand   the same mileage band
     */
    static String comparable(int yearSpan, boolean sameEngine, boolean sameBand) {
        StringBuilder rule = new StringBuilder("""
                c.model_id = l.model_id
                   AND c.id <> l.id
                   AND c.price_currency_code = l.price_currency_code
                   AND c.vehicle_condition <> 'NEW'
                   AND c.first_registration_year BETWEEN l.first_registration_year - %1$d
                                                     AND l.first_registration_year + %1$d
                """.formatted(yearSpan));
        if (sameEngine) {
            rule.append("""
                       AND c.fuel_type = l.fuel_type
                       AND c.power_hp BETWEEN l.power_hp * (1 - %1$s) AND l.power_hp * (1 + %1$s)
                    """.formatted(POWER_SPAN));
        }
        if (sameBand) {
            rule.append("   AND ").append(BAND.formatted("c")).append(" = ").append(BAND.formatted("l")).append('\n');
        }
        return rule.append("   AND ").append(ListingSearchRepository.visible("c")).toString();
    }

    /**
     * The price of the comparable {@code c} brought to the age and mileage of the listing
     * {@code l}, as SQL. See {@link #ADJUSTED_PRICE}.
     */
    static String adjustedPrice() {
        return ADJUSTED_PRICE;
    }

    /**
     * The comparables of the listing aliased {@code l}, as columns {@code mp.comparables} and
     * {@code mp.median}, the median of their prices adjusted to the listing. Joined to
     * nothing for a listing that cannot have an indicator, so those columns are then null.
     */
    static final String LATERAL = """
             LEFT JOIN LATERAL (
                SELECT count(*) AS comparables,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY %1$s) AS median
                  FROM listing c
                 WHERE %2$s
             ) mp ON l.model_id IS NOT NULL AND l.power_hp IS NOT NULL AND l.vehicle_condition <> 'NEW'
            """.formatted(ADJUSTED_PRICE, comparable(YEAR_SPAN, true, true));

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
