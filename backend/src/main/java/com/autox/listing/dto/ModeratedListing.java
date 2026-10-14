package com.autox.listing.dto;

import com.autox.listing.ModerationActionType;
import com.autox.user.AppUser;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A listing as a moderator sees it: everything needed to judge it, in one answer.
 *
 * <p>The whole listing rather than a summary — description, VIN, every photo — because
 * the point of the queue is to decide without opening anything else, and a moderator who
 * has to click through to read the description will stop reading descriptions.
 *
 * @param listing        the full listing, photos included
 * @param seller         who is selling, including the email a buyer never sees
 * @param allowedActions what this listing's state permits a moderator to do — the answer
 *                       to "would you accept this?", so that no client keeps its own copy
 *                       of the rules
 * @param trail          every moderation action on this listing, oldest first
 * @param editedSinceReview true when the listing is awaiting review <em>again</em>: it had
 *                       been approved or reinstated, and its seller has since changed its
 *                       content. The trail then ends in a decision that no longer stands,
 *                       and this is what says why.
 */
public record ModeratedListing(
        ListingResponse listing,
        ModeratedSeller seller,
        Set<ModerationActionType> allowedActions,
        List<ModerationEntry> trail,
        boolean editedSinceReview) {

    /** The seller, as an administrator may see them. */
    public record ModeratedSeller(
            UUID id,
            String displayName,
            String email,
            UserRole role,
            UserStatus status,
            Instant memberSince) {

        public static ModeratedSeller from(AppUser seller) {
            return new ModeratedSeller(
                    seller.getId(),
                    seller.getDisplayName(),
                    seller.getEmail(),
                    seller.getRole(),
                    seller.getStatus(),
                    seller.getCreatedAt());
        }
    }
}
