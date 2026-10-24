package com.autox.search;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * "Annonces similaires": the other live listings a buyer looking at one car would look at
 * next.
 *
 * <h2>The rule</h2>
 *
 * <p>Two kinds of match, the first preferred to the second:
 * <ol>
 *   <li><b>The same model</b>: the same catalogue make and model, registered within
 *       {@value #YEAR_SPAN} years of the listing and priced within {@link #SAME_MODEL_PRICE_SPAN}
 *       of it. This is {@link MarketPrice}'s comparable loosened: any engine, any mileage. The
 *       price band keeps out the same model in another league, such as a 30 000 € plug-in GT
 *       under an 18 000 € diesel, which is the same name and not the same purchase. Among them the comparables themselves come first
 *       (the same fuel and a power within {@link MarketPrice#POWER_SPAN}), then the nearest
 *       year, then the nearest mileage.</li>
 *   <li><b>The same kind of car for the money</b>: the same body type, priced within
 *       {@link #PRICE_SPAN} of the listing, whatever the make. The same fuel first, then the
 *       nearest price, then the nearest year. Only these can match a listing identified by
 *       free text, which has no catalogue model to share.</li>
 * </ol>
 *
 * <p>Up to {@value #MAX_SHOWN}, the same model first and the rest filled from the second.
 * Fewer than {@value #MIN_SHOWN} and none are shown: two weak suggestions under a listing
 * look like padding, and a buyer reads padding as a site with nothing to offer.
 *
 * <p>Never shown: the listing itself, anything not live (the same visibility predicate as
 * search), anything in another currency, and more than one of a seller's copies of the same
 * car, so a dealer with five identical Clios in stock fills one card, not five. "The same car"
 * is the same seller, model, version, year, fuel and gearbox; the mileage and the price may
 * differ. A seller's copies of the car being viewed are not shown at all: that car is
 * already on the page.
 *
 * <p>Computed when read, like the price indicator, and for the same reason: nothing can go
 * stale. Both kinds match on indexed columns: the same model on {@code ix_listing_live_market}
 * (model, year), the price band on {@code ix_listing_live_price}.
 */
@Component
public class SimilarListings {

    /** Fewer matches than this and the section is not shown. */
    public static final int MIN_SHOWN = 3;

    /** At most this many cards. */
    public static final int MAX_SHOWN = 6;

    /** How many years either side a listing of the same model may be registered. */
    public static final int YEAR_SPAN = 3;

    /** How far a listing of the same model may be priced from this one, as a fraction of its price. */
    public static final double SAME_MODEL_PRICE_SPAN = 0.40;

    /**
     * How far a listing of another model may be priced from this one, as a fraction of its
     * price. Narrower than for the same model: here the price is most of what makes it similar.
     */
    public static final double PRICE_SPAN = 0.20;

    /**
     * What makes two listings of a seller's "the same car", as one value: the listing aliased
     * {@code %1$s}. Lower-cased free text, so "Golf" and "golf " are one car.
     */
    private static final String SAME_CAR = """
            concat_ws('|', %1$s.seller_id,
                      coalesce(%1$s.model_id::text, lower(btrim(%1$s.make_text)) || '/' || lower(btrim(%1$s.model_text)), ''),
                      coalesce(%1$s.trim_id::text, lower(btrim(%1$s.version_text)), ''),
                      %1$s.first_registration_year,
                      coalesce(%1$s.fuel_type, ''),
                      coalesce(%1$s.gearbox, ''))""";

    /**
     * Every match, one row per (match, kind), ranked by {@code kind}, {@code closeness},
     * {@code distance} and {@code tiebreak}, lowest first. A listing that matches both ways
     * appears twice, and the copies step keeps its better row.
     */
    private static final String CANDIDATES = """
            SELECT c.id, 1 AS kind,
                   CASE WHEN c.fuel_type = t.fuel_type
                         AND c.power_hp BETWEEN t.power_hp * (1 - %3$s) AND t.power_hp * (1 + %3$s)
                        THEN 0 ELSE 1 END AS closeness,
                   abs(c.first_registration_year - t.first_registration_year)::numeric AS distance,
                   abs(c.mileage_km - t.mileage_km)::numeric AS tiebreak,
                   %4$s AS car, %5$s AS listed_car
              FROM target t
              JOIN listing c ON c.model_id = t.model_id
             WHERE c.first_registration_year BETWEEN t.first_registration_year - %1$d
                                                 AND t.first_registration_year + %1$d
               AND c.price_amount BETWEEN t.price_amount * (1 - %7$s) AND t.price_amount * (1 + %7$s)
               AND c.id <> t.id
               AND c.price_currency_code = t.price_currency_code
               AND %6$s
            UNION ALL
            SELECT c.id, 2 AS kind,
                   CASE WHEN c.fuel_type = t.fuel_type THEN 0 ELSE 1 END AS closeness,
                   abs(c.price_amount - t.price_amount)::numeric / t.price_amount AS distance,
                   abs(c.first_registration_year - t.first_registration_year)::numeric AS tiebreak,
                   %4$s AS car, %5$s AS listed_car
              FROM target t
              JOIN listing c ON c.body_type = t.body_type
             WHERE c.price_amount BETWEEN t.price_amount * (1 - %2$s) AND t.price_amount * (1 + %2$s)
               AND c.id <> t.id
               AND c.price_currency_code = t.price_currency_code
               AND %6$s
            """.formatted(YEAR_SPAN, PRICE_SPAN, MarketPrice.POWER_SPAN,
                    SAME_CAR.formatted("c"), SAME_CAR.formatted("t"), ListingSearchRepository.visible("c"),
                    SAME_MODEL_PRICE_SPAN);

    private static final String SQL = """
            WITH target AS (SELECT * FROM listing WHERE id = :id),
            candidates AS (%1$s),
            one_per_car AS (
                SELECT DISTINCT ON (car) *
                  FROM candidates
                 WHERE car <> listed_car
                 ORDER BY car, kind, closeness, distance, tiebreak, id
            ),
            chosen AS (
                SELECT id, row_number() OVER (ORDER BY kind, closeness, distance, tiebreak, id) AS position
                  FROM one_per_car
                 ORDER BY position
                 LIMIT :limit
            )
            %2$s
              JOIN chosen ON chosen.id = l.id
             ORDER BY chosen.position
            """.formatted(CANDIDATES, ListingSearchRepository.SELECT);

    private final NamedParameterJdbcTemplate jdbc;

    public SimilarListings(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The listings similar to the given one, best first: between {@value #MIN_SHOWN} and
     * {@value #MAX_SHOWN}, or none. The caller checks that the listing itself is live.
     */
    public List<ListingSearchRepository.SearchRow> find(UUID listingId) {
        List<ListingSearchRepository.SearchRow> rows = jdbc.query(SQL,
                new MapSqlParameterSource("id", listingId).addValue("limit", MAX_SHOWN),
                ListingSearchRepository::mapRow);
        return rows.size() < MIN_SHOWN ? List.of() : rows;
    }
}
