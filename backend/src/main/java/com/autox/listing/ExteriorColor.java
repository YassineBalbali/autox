package com.autox.listing;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Searchable colour family. Mirrors ck_listing_exterior_color.
 * The manufacturer's own colour name goes in {@code Listing.colorText}.
 */
@Schema(enumAsRef = true)
public enum ExteriorColor {
    BLACK,
    WHITE,
    GREY,
    SILVER,
    BLUE,
    RED,
    GREEN,
    BROWN,
    BEIGE,
    YELLOW,
    ORANGE,
    PURPLE,
    GOLD,
    OTHER
}
