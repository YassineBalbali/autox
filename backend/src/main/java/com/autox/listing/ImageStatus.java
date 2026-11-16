package com.autox.listing;

import io.swagger.v3.oas.annotations.media.Schema;

/** Mirrors ck_listing_image_status. */
@Schema(enumAsRef = true)
public enum ImageStatus {
    /** Pre-signed URL issued, object not yet confirmed in storage. */
    PENDING,
    READY,
    /**
     * No longer written: a rejected upload is now deleted with its files (see
     * {@code ListingImageService#confirmUpload}). Rows written by earlier versions are removed
     * by {@code ListingImageSweeper}; the value stays because the database and the API's enum
     * still know it.
     */
    FAILED
}
