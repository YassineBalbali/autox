package com.autox.listing;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Rendition sizes produced from an uploaded original, named after where they are shown
 * rather than how big they are. Mirrors ck_listing_image_variant.
 */
@Schema(enumAsRef = true)
public enum ImageVariantType {

    /** Gallery strip and seller list rows. */
    THUMBNAIL,

    /** Search-result card, and the detail page's default. */
    CARD,

    /** Full-screen viewer. */
    FULL
}
