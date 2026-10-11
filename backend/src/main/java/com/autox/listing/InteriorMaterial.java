package com.autox.listing;

import io.swagger.v3.oas.annotations.media.Schema;

/** Mirrors ck_listing_interior_material. */
@Schema(enumAsRef = true)
public enum InteriorMaterial {
    CLOTH,
    LEATHER,
    PART_LEATHER,
    ALCANTARA,
    VELOUR,
    OTHER
}
