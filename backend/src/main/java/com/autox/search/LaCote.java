package com.autox.search;

import com.autox.api.error.ApiException;
import com.autox.catalog.VehicleModelRepository;
import com.autox.reference.Currency;
import com.autox.reference.CurrencyRepository;
import com.autox.search.dto.Valuation;
import com.autox.search.dto.ValuationEngine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "La Cote": what a car described by a visitor is worth on AutoX.
 *
 * <p>The price indicator's engine run the other way round. The indicator takes a listing and
 * asks where its price sits among its comparables; La Cote takes a car that is not listed and
 * asks what its comparables are listed for. Same comparables ({@link MarketPrice#comparable}),
 * same adjustment of their prices to the car's age and mileage, same minimum of
 * {@value MarketPrice#MIN_COMPARABLES}. The described car is put into the indicator's query
 * as a listing of its own, a row with the columns the rule reads, so there is no second copy
 * of the rule to drift from the first.
 *
 * <p>What La Cote adds is a range, the interquartile range of the adjusted prices, because a
 * single figure overstates how precisely five or ten asking prices pin a value down. When
 * there are too few comparables it gives no figure at all, and instead lists the loosenings of
 * the rule ({@link ValuationScope}) that would find enough, each with its count.
 */
@Service
public class LaCote {

    /** An id no listing has, so the rule's "not the listing itself" excludes nothing. */
    private static final String NO_LISTING = "00000000-0000-0000-0000-000000000000";

    /** The described car, as the listing {@code l} that {@link MarketPrice#comparable} reads. */
    private static final String DESCRIBED_CAR = """
            (SELECT CAST(:modelId AS uuid)            AS model_id,
                    CAST('%s' AS uuid)                AS id,
                    CAST(:currency AS varchar)        AS price_currency_code,
                    CAST(:fuelType AS varchar)        AS fuel_type,
                    CAST(:powerHp AS integer)         AS power_hp,
                    CAST(:year AS integer)            AS first_registration_year,
                    CAST(:month AS integer)           AS first_registration_month,
                    CAST(:mileageKm AS integer)       AS mileage_km) l""".formatted(NO_LISTING);

    private static final String ESTIMATE = """
            SELECT count(*)                                                  AS comparables,
                   percentile_cont(0.25) WITHIN GROUP (ORDER BY %1$s)       AS low,
                   percentile_cont(0.5)  WITHIN GROUP (ORDER BY %1$s)       AS median,
                   percentile_cont(0.75) WITHIN GROUP (ORDER BY %1$s)       AS high,
                   min(c.first_registration_year)                            AS year_from,
                   max(c.first_registration_year)                            AS year_to,
                   min(c.mileage_km)                                         AS mileage_from,
                   max(c.mileage_km)                                         AS mileage_to
              FROM %2$s
              JOIN listing c ON %3$s""";

    private static final String COUNT = "SELECT count(*) FROM %s JOIN listing c ON %s";

    /**
     * The engines of a model on AutoX now: live used listings that state a fuel and a power,
     * grouped by both. The most listed first.
     */
    private static final String ENGINES = """
            SELECT l.fuel_type, l.power_hp, count(*) AS listings,
                   mode() WITHIN GROUP (ORDER BY nullif(btrim(l.version_text), '')) AS version
              FROM listing l
             WHERE l.model_id = :modelId
               AND l.fuel_type IS NOT NULL
               AND l.power_hp IS NOT NULL
               AND l.vehicle_condition <> 'NEW'
               AND %s
             GROUP BY l.fuel_type, l.power_hp
             ORDER BY listings DESC, l.power_hp, l.fuel_type""".formatted(ListingSearchRepository.visible("l"));

    private final NamedParameterJdbcTemplate jdbc;
    private final VehicleModelRepository models;
    private final CurrencyRepository currencies;

    public LaCote(NamedParameterJdbcTemplate jdbc, VehicleModelRepository models, CurrencyRepository currencies) {
        this.jdbc = jdbc;
        this.models = models;
        this.currencies = currencies;
    }

    /** The engines a visitor can choose from for this model: those listed now. 404 for an unknown model. */
    @Transactional(readOnly = true)
    public List<ValuationEngine> engines(UUID modelId) {
        requireModel(modelId);
        return jdbc.query(ENGINES, new MapSqlParameterSource("modelId", modelId), (rs, row) -> new ValuationEngine(
                rs.getString("fuel_type"), rs.getInt("power_hp"), rs.getString("version"), rs.getInt("listings")));
    }

    @Transactional(readOnly = true)
    public Valuation value(ValuationRequest car) {
        requireModel(car.modelId());
        Currency currency = currencies.findById(car.currencyOrEuro())
                .orElseThrow(() -> ApiException.validationFailed("Unknown currency " + car.currencyOrEuro() + "."));
        ValuationScope scope = car.scopeOrExact();
        MapSqlParameterSource parameters = parameters(car, currency);

        String sql = ESTIMATE.formatted(MarketPrice.adjustedPrice(), DESCRIBED_CAR, scope.comparable());
        return jdbc.queryForObject(sql, parameters, (rs, row) -> {
            int count = rs.getInt("comparables");
            boolean enough = count >= MarketPrice.MIN_COMPARABLES;
            long step = hundred(currency);

            Valuation.Estimate estimate = enough
                    ? new Valuation.Estimate(roundTo(rs.getDouble("low"), step), roundTo(rs.getDouble("median"), step),
                            roundTo(rs.getDouble("high"), step), currency.getCode())
                    : null;
            Valuation.Basis basis = count == 0 ? null : new Valuation.Basis(
                    rs.getInt("year_from"), rs.getInt("year_to"), rs.getInt("mileage_from"), rs.getInt("mileage_to"));

            return new Valuation(scope, count, MarketPrice.MIN_COMPARABLES, estimate, basis,
                    window(car, scope), enough ? List.of() : widerThan(scope, parameters));
        });
    }

    /** The loosenings, other than the one asked for, that find enough comparables. */
    private List<Valuation.Widening> widerThan(ValuationScope asked, MapSqlParameterSource parameters) {
        List<Valuation.Widening> wider = new ArrayList<>();
        for (ValuationScope scope : ValuationScope.values()) {
            if (scope == ValuationScope.EXACT || scope == asked) {
                continue;
            }
            Integer count = jdbc.queryForObject(COUNT.formatted(DESCRIBED_CAR, scope.comparable()), parameters, Integer.class);
            if (count != null && count >= MarketPrice.MIN_COMPARABLES) {
                wider.add(new Valuation.Widening(scope, count));
            }
        }
        return wider;
    }

    private static Valuation.Window window(ValuationRequest car, ValuationScope scope) {
        MarketPrice.MileageBand band = MarketPrice.bandOf(car.mileageKm());
        boolean engine = scope.sameEngine();
        return new Valuation.Window(
                car.year() - scope.yearSpan(), car.year() + scope.yearSpan(),
                scope.sameBand() ? band.fromKm() : null,
                scope.sameBand() ? band.toKm() : null,
                engine ? car.fuelType().name() : null,
                engine ? powerBound(car.powerHp(), -1) : null,
                engine ? powerBound(car.powerHp(), 1) : null);
    }

    /**
     * The lowest ({@code sign} -1) or highest (+1) whole power the rule accepts. In decimal, as
     * PostgreSQL computes it: in floating point 100 × 1.15 is 114.999…, and 115 would be left out.
     */
    private static int powerBound(int powerHp, int sign) {
        BigDecimal span = BigDecimal.valueOf(MarketPrice.POWER_SPAN).multiply(BigDecimal.valueOf(sign));
        BigDecimal bound = BigDecimal.valueOf(powerHp).multiply(BigDecimal.ONE.add(span));
        return bound.setScale(0, sign < 0 ? RoundingMode.CEILING : RoundingMode.FLOOR).intValueExact();
    }

    private static MapSqlParameterSource parameters(ValuationRequest car, Currency currency) {
        return new MapSqlParameterSource()
                .addValue("modelId", car.modelId())
                .addValue("currency", currency.getCode())
                .addValue("fuelType", car.fuelType().name())
                .addValue("powerHp", car.powerHp())
                .addValue("year", car.year())
                .addValue("month", car.month())
                .addValue("mileageKm", car.mileageKm());
    }

    /** A hundred of the currency's units, in its minor unit: 10 000 for the euro. */
    private static long hundred(Currency currency) {
        return 100L * (long) Math.pow(10, currency.getMinorUnit());
    }

    private static long roundTo(double amount, long step) {
        return Math.round(amount / step) * step;
    }

    private void requireModel(UUID modelId) {
        if (!models.existsById(modelId)) {
            throw ApiException.notFound("Model");
        }
    }
}
