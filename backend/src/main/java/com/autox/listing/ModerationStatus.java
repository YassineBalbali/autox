package com.autox.listing;

import io.swagger.v3.oas.annotations.media.Schema;

/** Admin verdict, independent of {@link ListingStatus}. Mirrors ck_listing_moderation_status. */
@Schema(enumAsRef = true)
public enum ModerationStatus {
    NOT_REVIEWED,
    APPROVED,
    REJECTED
}
