package com.autox.listing;

import io.swagger.v3.oas.annotations.media.Schema;
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
 * applied. It describes every move the API can make and no move it cannot. Whether a
 * quota permits a move is a separate question, answered by the service.
 *
 * <pre>
 *   DRAFT ──publish──▶ ACTIVE ──unpublish──▶ PAUSED ──publish──▶ ACTIVE
 *                        │                     │
 *                        ├──▶ SOLD ◀───sold────┘
 *                        ├──▶ EXPIRED ──renew──▶ ACTIVE
 *                        └──▶ REMOVED ──reinstate (moderator only)──▶ ACTIVE | PAUSED | EXPIRED
 * </pre>
 *
 * <p><b>Out of REMOVED is a moderator's move, never a seller's.</b> A listing reaches
 * REMOVED two ways. A seller's delete also stamps {@code deleted_at}, and the row then
 * exists for no read path at all: it is gone. A moderator's refusal or takedown sets
 * REMOVED alone, and that listing can be reinstated — by a moderator. The seller it
 * belongs to still sees it, and must not be able to put it back online themselves, which
 * is why {@link #sellerTargets()} exists beside {@link #allowedTargets()}.
 */
@Schema(enumAsRef = true)
public enum ListingStatus {

    DRAFT,

    /**
     * Reserved, and unreachable: no transition leads here. The MVP publishes straight to
     * ACTIVE and moderates afterwards — "awaiting review" is ACTIVE with a moderation
     * status of NOT_REVIEWED. The value exists, here and in ck_listing_status, so that
     * pre-publication review can be switched on without a migration; its transitions are
     * added to the table the day an endpoint makes them.
     */
    PENDING_REVIEW,

    ACTIVE,

    /** Hidden by the seller, and freely restored. Does not count against the active quota. */
    PAUSED,

    SOLD,

    /** Passed {@code expires_at}. Renewable. */
    EXPIRED,

    /**
     * Refused or taken down by a moderator (reversible), or deleted by the seller
     * (permanent: {@code deleted_at} is set as well).
     */
    REMOVED;

    private static final Map<ListingStatus, Set<ListingStatus>> ALLOWED = allowedTransitions();

    private static Map<ListingStatus, Set<ListingStatus>> allowedTransitions() {
        Map<ListingStatus, Set<ListingStatus>> allowed = new EnumMap<>(ListingStatus.class);
        allowed.put(DRAFT, EnumSet.of(ACTIVE, REMOVED));
        // Nothing leads here and nothing leaves: see the constant.
        allowed.put(PENDING_REVIEW, EnumSet.noneOf(ListingStatus.class));
        allowed.put(ACTIVE, EnumSet.of(PAUSED, SOLD, EXPIRED, REMOVED));
        allowed.put(PAUSED, EnumSet.of(ACTIVE, SOLD, REMOVED));
        // Sold is kept for the record, and can only be withdrawn.
        allowed.put(SOLD, EnumSet.of(REMOVED));
        allowed.put(EXPIRED, EnumSet.of(ACTIVE, REMOVED));
        // Reinstatement by a moderator. Back online; or paused when the seller's quota
        // has filled meanwhile; or expired when the listing's window ran out while it
        // was down. See ListingModerationService#reinstate.
        allowed.put(REMOVED, EnumSet.of(ACTIVE, PAUSED, EXPIRED));

        allowed.replaceAll((status, targets) -> Collections.unmodifiableSet(targets));
        return Collections.unmodifiableMap(allowed);
    }

    public boolean canTransitionTo(ListingStatus target) {
        return target != null && ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    /** Every state reachable from this one, by anyone. */
    public Set<ListingStatus> allowedTargets() {
        return ALLOWED.getOrDefault(this, Set.of());
    }

    /**
     * The states the listing's own seller may move it to from this one.
     *
     * <p>The same as {@link #allowedTargets()} except out of REMOVED, where it is empty:
     * a listing a moderator took down is not the seller's to put back. This is what a
     * seller-facing response reports as {@code allowedTransitions}, and what the seller's
     * endpoints check.
     */
    public Set<ListingStatus> sellerTargets() {
        return this == REMOVED ? Set.of() : allowedTargets();
    }

    public boolean canSellerTransitionTo(ListingStatus target) {
        return target != null && sellerTargets().contains(target);
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
