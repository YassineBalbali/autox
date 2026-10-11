package com.autox.catalog;

import io.swagger.v3.oas.annotations.media.Schema;

/** Mirrors ck_listing_gearbox / ck_vehicle_trim_gearbox. */
@Schema(enumAsRef = true)
public enum Gearbox {
    MANUAL,
    AUTOMATIC,
    SEMI_AUTOMATIC
}
