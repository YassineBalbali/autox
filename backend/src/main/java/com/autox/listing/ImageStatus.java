package com.autox.listing;

import io.swagger.v3.oas.annotations.media.Schema;

/** Mirrors ck_listing_image_status. */
@Schema(enumAsRef = true)
public enum ImageStatus {
    /** Pre-signed URL issued, object not yet confirmed in storage. */
    PENDING,
    READY,
    FAILED
}
