package com.autox.listing;

/** Mirrors ck_listing_image_status. */
public enum ImageStatus {
    /** Pre-signed URL issued, object not yet confirmed in storage. */
    PENDING,
    READY,
    FAILED
}
