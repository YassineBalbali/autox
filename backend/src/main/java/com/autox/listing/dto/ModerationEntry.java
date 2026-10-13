package com.autox.listing.dto;

import com.autox.listing.ModerationAction;
import com.autox.listing.ModerationActionType;
import com.autox.listing.ModerationReason;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of a listing's moderation trail: who did what, when, and why.
 *
 * <p>Admin-only. It names the moderator and carries their internal note, neither of which
 * a seller is shown — a seller sees the reason code, on their own listing, and no more.
 */
public record ModerationEntry(
        UUID id,
        ModerationActionType action,
        /** Present for REJECT and REMOVE. Null for a code this build no longer knows. */
        ModerationReason reasonCode,
        String note,
        UUID moderatorId,
        String moderatorName,
        Instant at) {

    public static ModerationEntry from(ModerationAction action) {
        return new ModerationEntry(
                action.getId(),
                action.getAction(),
                reasonOf(action),
                action.getNote(),
                action.getAdmin().getId(),
                action.getAdmin().getDisplayName(),
                action.getCreatedAt());
    }

    /**
     * The stored code as a reason, or null.
     *
     * <p>The column is free text by design — the trail outlives any one version of the
     * list — so a code written by a build that has since retired it must not fail the read
     * of an entire trail.
     */
    public static ModerationReason reasonOf(ModerationAction action) {
        String code = action.getReasonCode();
        if (code == null) {
            return null;
        }
        try {
            return ModerationReason.valueOf(code);
        } catch (IllegalArgumentException retired) {
            return null;
        }
    }
}
