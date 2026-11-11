package com.autox.listing;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A listing's Crit'Air class, estimated from its fuel and first registration by the official
 * table (arrêté du 21 juin 2016, annexe I). The rule is the database function
 * {@code crit_air_class} (V30), stored on each listing as {@code crit_air}: it exists once, and
 * the search filters on the same column. Absent when the fuel or the date cannot settle it.
 */
@Schema(enumAsRef = true)
public enum CritAir {
    /** The green vignette: electric and hydrogen. */
    ZERO,
    ONE,
    TWO,
    THREE,
    FOUR,
    FIVE,
    /** Too old for any vignette: petrol or diesel before 1997. */
    UNCLASSIFIED;

    /** From the stored code: 0 to 5, 6 for unclassified; null for none. */
    public static CritAir fromCode(Short code) {
        if (code == null) {
            return null;
        }
        return values()[code];
    }
}
