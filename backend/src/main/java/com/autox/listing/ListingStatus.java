package com.autox.listing;

/**
 * Seller-facing lifecycle. Mirrors ck_listing_status.
 *
 * <p>Allowed transitions:
 * DRAFT -> ACTIVE | PENDING_REVIEW; ACTIVE -> PAUSED | SOLD | EXPIRED | REMOVED;
 * PAUSED -> ACTIVE | REMOVED; EXPIRED -> ACTIVE; REMOVED -> ACTIVE (admin reinstate).
 */
public enum ListingStatus {
    DRAFT,
    /**
     * Reserved. The MVP publishes straight to ACTIVE and moderates afterwards;
     * this state exists so pre-publish review can be switched on without a migration.
     */
    PENDING_REVIEW,
    ACTIVE,
    PAUSED,
    SOLD,
    EXPIRED,
    /** Taken down by an admin, or deleted by the seller after publication. */
    REMOVED
}
