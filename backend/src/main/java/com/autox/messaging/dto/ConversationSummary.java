package com.autox.messaging.dto;

import com.autox.messaging.ConversationRole;
import com.autox.user.UserRole;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of an inbox.
 *
 * <p>The other party is a display name and a role, never an email or a phone number: a buyer
 * and a seller reach each other only through these conversations.
 *
 * @param role        which side the caller is on
 * @param unreadCount messages from the other party the caller has not opened
 * @param canReply    false once the listing has been taken down or deleted, or either
 *                    account is closed or suspended; the conversation stays readable
 */
public record ConversationSummary(
        UUID id,
        ConversationRole role,
        ListingRef listing,
        Party counterpart,
        LastMessage lastMessage,
        int unreadCount,
        boolean canReply,
        Instant lastMessageAt) {

    /**
     * The listing the conversation is about, as it is now.
     *
     * @param online   whether it can be found and opened by the public at the moment
     * @param coverUrl its first photo, small; null when it has none
     */
    public record ListingRef(UUID id, String title, long priceAmount, String currencyCode,
                             boolean online, String coverUrl) {
    }

    /** The other side: a display name and whether it is a trade seller. */
    public record Party(UUID id, String displayName, UserRole role) {
    }

    /**
     * @param preview the start of the message, or null when a moderator hid it
     */
    public record LastMessage(String preview, boolean sentByMe, boolean hidden, Instant sentAt) {
    }
}
