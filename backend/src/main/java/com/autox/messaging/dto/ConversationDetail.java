package com.autox.messaging.dto;

import com.autox.messaging.ConversationRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A conversation with its messages, oldest first.
 *
 * @param earlierOmitted true when the conversation has more messages than are returned
 *                       ({@code MessagingService.MESSAGES_SHOWN}); the oldest are left out
 */
public record ConversationDetail(
        UUID id,
        ConversationRole role,
        ConversationSummary.ListingRef listing,
        ConversationSummary.Party counterpart,
        boolean canReply,
        List<MessageView> messages,
        boolean earlierOmitted) {

    /**
     * @param body         null when a moderator hid the message
     * @param reportedByMe whether the caller has reported it; a message can be reported once
     */
    public record MessageView(UUID id, boolean sentByMe, String body, boolean hidden,
                              boolean reportedByMe, Instant sentAt) {
    }
}
