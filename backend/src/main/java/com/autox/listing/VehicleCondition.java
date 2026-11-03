package com.autox.listing;

import io.swagger.v3.oas.annotations.media.Schema;

/** Mirrors ck_listing_condition. */
@Schema(enumAsRef = true)
public enum VehicleCondition {
    NEW,
    USED
}
