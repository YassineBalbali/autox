package com.autox.listing;

/**
 * Rendition sizes produced from an uploaded original, named after where they are shown
 * rather than how big they are. Mirrors ck_listing_image_variant.
 */
public enum ImageVariantType {

    /** Gallery strip and seller list rows. */
    THUMBNAIL,

    /** Search-result card, and the detail page's default. */
    CARD,

    /** Full-screen viewer. */
    FULL
}
