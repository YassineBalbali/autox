package com.autox.listing;

import io.swagger.v3.oas.annotations.media.Schema;

/** Mirrors ck_listing_emission_class. Drives low-emission-zone filters in FR/DE. */
@Schema(enumAsRef = true)
public enum EmissionClass {
    EURO_1,
    EURO_2,
    EURO_3,
    EURO_4,
    EURO_5,
    EURO_6,
    EURO_6D,
    EURO_7,
    OTHER
}
