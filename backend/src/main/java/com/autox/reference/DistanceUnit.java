package com.autox.reference;

import io.swagger.v3.oas.annotations.media.Schema;

/** Mirrors ck_country_distance_unit. Presentation only. */
@Schema(enumAsRef = true)
public enum DistanceUnit {
    KM,
    MILE
}
