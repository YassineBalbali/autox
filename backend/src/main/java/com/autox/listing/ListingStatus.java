package com.autox.listing;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Seller-facing lifecycle, and the authority on which moves between states are legal.
 * Mirrors ck_listing_status.
 *
 * <p>The transition table lives here rather than in the service so there is exactly one
 * definition of what is allowed, and an illegal move is rejected rather than quietly
 * applied. Transitions are about the shape of the lifecycle only — who may make a move,
 * and whether a quota permits it, are separate questions answered by the service.
 *
 * <pre>
 *   DRAFT ──publish──▶ ACTIVE ──unpublish──▶ PAUSED ──publish──▶ ACTIVE
 *                        │                     │
 *                        ├──▶ SOLD ◀───sold────┘
 *                        ├──▶ EXPIRED ──renew──▶ ACTIVE
 *                        └──▶ REMOVED  (terminal, see below)
 * </pre>
 */
public enum ListingStatus {

    DRAFT,

    /**
     * Reserved. The MVP publishes straight to ACTIVE and moderates afterwards; this
     * state exists so pre-publish review can be switched on without a migration.
     */
    PENDING_REVIEW,

    ACTIVE,

    /** Hidden by the seller, and freely restored. Does not count against the active quota. */
    PAUSED,

    SOLD,

    /** Passed {@code expires_at}. Renewable. */
    EXPIRED,

    /** Taken down by an admin, or deleted by the seller. */
    REMOVED;

    private static final Map<ListingStatus, Set<ListingStatus>> ALLOWED = allowedTransitions();

    private static Map<ListingStatus, Set<ListingStatus>> allowedTransitions() {
        Map<ListingStatus, Set<ListingStatus>> allowed = new EnumMap<>(ListingStatus.class);
        allowed.put(DRAFT, EnumSet.of(ACTIVE, PENDING_REVIEW, REMOVED));
        allowed.put(PENDING_REVIEW, EnumSet.of(ACTIVE, DRAFT, REMOVED));
        allowed.put(ACTIVE, EnumSet.of(PAUSED, SOLD, EXPIRED, REMOVED));
        allowed.put(PAUSED, EnumSet.of(ACTIVE, SOLD, REMOVED));
        // Sold is kept for the record, and can only be withdrawn.
        allowed.put(SOLD, EnumSet.of(REMOVED));
        allowed.put(EXPIRED, EnumSet.of(ACTIVE, REMOVED));
        // Terminal for now. Reinstatement after an admin takedown becomes legal when the
        // moderation endpoints land, together with the split that makes it reachable:
        // a seller delete sets deleted_at (permanent), an admin takedown sets REMOVED
        // alone (reversible). Until then this table does not promise a move the API
        // cannot make.
        allowed.put(REMOVED, EnumSet.noneOf(ListingStatus.class));

        allowed.replaceAll((status, targets) -> Collections.unmodifiableSet(targets));
        return Collections.unmodifiableMap(allowed);
    }

    public boolean canTransitionTo(ListingStatus target) {
        return target != null && ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public Set<ListingStatus> allowedTargets() {
        return ALLOWED.getOrDefault(this, Set.of());
    }

    /** Counts against the seller's active-listing quota. */
    public boolean occupiesQuota() {
        return this == ACTIVE;
    }

    /** May the seller still change the listing's content in this state? */
    public boolean isEditable() {
        return this == DRAFT || this == PENDING_REVIEW || this == ACTIVE || this == PAUSED || this == EXPIRED;
    }
}
