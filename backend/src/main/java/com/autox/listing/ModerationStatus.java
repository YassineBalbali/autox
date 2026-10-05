package com.autox.listing;

/** Admin verdict, independent of {@link ListingStatus}. Mirrors ck_listing_moderation_status. */
public enum ModerationStatus {
    NOT_REVIEWED,
    APPROVED,
    REJECTED
}
