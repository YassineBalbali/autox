package com.autox.catalog;

import io.swagger.v3.oas.annotations.media.Schema;

/** Mirrors ck_listing_body_type / ck_vehicle_trim_body_type. */
@Schema(enumAsRef = true)
public enum BodyType {
    HATCHBACK,
    SEDAN,
    ESTATE,
    SUV,
    COUPE,
    CONVERTIBLE,
    MPV,
    PICKUP,
    VAN,
    OTHER
}
