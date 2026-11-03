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
    ALL
}
