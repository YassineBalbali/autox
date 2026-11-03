package com.autox.listing;

import io.swagger.v3.oas.annotations.media.Schema;

/** What happened to a damaged vehicle. Several may apply. Mirrors ck_listing_damage. */
@Schema(enumAsRef = true)
public enum DamageType {
    BODYWORK,
    MECHANICAL,
    HAIL,
    FLOOD,
    OTHER
}
