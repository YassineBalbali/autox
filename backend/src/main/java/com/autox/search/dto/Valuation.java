package com.autox.search.dto;

import com.autox.search.ValuationScope;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * La Cote's answer for one car: an estimate with its range, or the plain statement that
 * there is not enough to estimate from, and in either case what it rests on.
 *
 * <p>Every amount is an <b>asking price</b> on AutoX, in the currency's minor unit, brought
 * to the car's age and mileage. It is not what cars sell for, which is usually a little less.
 *
 * @param scope              the comparables rule used: the price indicator's, or one loosening
 * @param comparableCount    how many live listings matched
 * @param minimumComparables below this, no estimate is given
 * @param estimate           null when there are fewer than {@code minimumComparables}
 * @param basis              the comparables actually found; null when there are none
 * @param window             what was searched for
 * @param widerScopes        when there is no estimate: the loosenings that would give one,
 *                           with how many they find. Empty otherwise
 */
public record Valuation(
        ValuationScope scope,
        int comparableCount,
        int minimumComparables,
        Estimate estimate,
        Basis basis,
        Window window,
        List<Widening> widerScopes) {

    /**
     * The median of the adjusted prices, with the middle half of them either side: a quarter
     * of the comparables are priced below {@code lowAmount} and a quarter above
     * {@code highAmount}. Each is rounded to the nearest hundred: a valuation to the euro
     * would claim a precision it does not have.
     */
    @Schema(name = "ValuationEstimate")
    public record Estimate(long lowAmount, long medianAmount, long highAmount, String currencyCode) {
    }

    /** The years and mileages of the comparables found, as listed, before any adjustment. */
    @Schema(name = "ValuationBasis")
    public record Basis(int yearFrom, int yearTo, int mileageFromKm, int mileageToKm) {
    }

    /**
     * The conditions the comparables met. {@code mileageToKm} is null for the open-ended top
     * band; the mileage and power bounds are null when the scope does not match on them.
     */
    @Schema(name = "ValuationWindow")
    public record Window(int yearFrom, int yearTo,
                         Integer mileageFromKm, Integer mileageToKm,
                         String fuelType, Integer powerFromHp, Integer powerToHp) {
    }

    @Schema(name = "ValuationWidening")
    public record Widening(ValuationScope scope, int comparableCount) {
    }
}
