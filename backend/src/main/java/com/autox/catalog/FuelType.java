package com.autox.catalog;

import io.swagger.v3.oas.annotations.media.Schema;

/** Mirrors ck_listing_fuel_type / ck_vehicle_trim_fuel_type. */
@Schema(enumAsRef = true)
public enum FuelType {
    PETROL,
    DIESEL,
    HYBRID_PETROL,
    HYBRID_DIESEL,
    PLUGIN_HYBRID,
    ELECTRIC,
    LPG,
    CNG,
    HYDROGEN,
    ETHANOL,
    OTHER
}
