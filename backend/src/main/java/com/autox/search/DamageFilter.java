package com.autox.search;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Whether a search includes damaged vehicles. Leaving it out means {@link #UNDAMAGED}: nobody
 * looking for a car to drive should have to ask not to be shown wrecks.
 */
@Schema(enumAsRef = true)
public enum DamageFilter {
    UNDAMAGED,
    DAMAGED_ONLY,
    ALL;

    /**
     * Whether "bonnes affaires" can find anything alongside this. A damaged vehicle never gets a
     * price verdict, so with damaged vehicles alone it never can.
     */
    public static boolean allowsGoodDeals(DamageFilter filter) {
        return filter != DAMAGED_ONLY;
    }
}
