package com.autox.catalog;

import io.swagger.v3.oas.annotations.media.Schema;

/** Mirrors ck_listing_drivetrain / ck_vehicle_trim_drivetrain. */
@Schema(enumAsRef = true)
public enum Drivetrain {
    FWD,
    RWD,
    AWD
}
