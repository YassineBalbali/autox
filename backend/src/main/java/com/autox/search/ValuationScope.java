package com.autox.search;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Which comparables La Cote valued a car against: the price indicator's rule, or that rule
 * loosened one way when the car has too few comparables under it.
 *
 * <p>Each loosening drops or widens one condition, never two, so a buyer reading "toutes
 * motorisations" knows exactly what the figure no longer accounts for.
 */
@Schema(enumAsRef = true)
public enum ValuationScope {

    /** The price indicator's rule, unchanged. */
    EXACT(MarketPrice.YEAR_SPAN, true, true),
    /** Any engine of the model: neither fuel nor power is matched. */
    ALL_ENGINES(MarketPrice.YEAR_SPAN, false, true),
    /** Four years either side of the car's first registration, not two. */
    WIDER_YEARS(4, true, true),
    /** Any mileage, not only the car's mileage band. */
    ANY_MILEAGE(MarketPrice.YEAR_SPAN, true, false);

    private final int yearSpan;
    private final boolean sameEngine;
    private final boolean sameBand;

    ValuationScope(int yearSpan, boolean sameEngine, boolean sameBand) {
        this.yearSpan = yearSpan;
        this.sameEngine = sameEngine;
        this.sameBand = sameBand;
    }

    int yearSpan() {
        return yearSpan;
    }

    boolean sameEngine() {
        return sameEngine;
    }

    boolean sameBand() {
        return sameBand;
    }

    /** The comparables rule for this scope, as SQL over {@code c} and {@code l}. */
    String comparable() {
        return MarketPrice.comparable(yearSpan, sameEngine, sameBand);
    }
}
