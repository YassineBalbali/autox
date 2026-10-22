package com.autox.messaging.dto;

import com.autox.messaging.MessageReportReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An open report, for a moderator: the message, who reported it and why, and the messages
 * around it so it can be judged in context.
 *
 * <p>A moderator sees a conversation only through a report on it. There is no endpoint that
 * lets an administrator browse conversations nobody has reported.
 *
 * @param context up to {@code MessagingService.REPORT_CONTEXT} messages either side of the
 *                reported one, and it, oldest first
 */
public record ReportedMessage(
        UUID reportId,
        MessageReportReason reason,
        String note,
        Instant reportedAt,
        ConversationSummary.Party reporter,
        ConversationSummary.Party sender,
        ConversationSummary.ListingRef listing,
        UUID messageId,
        List<ContextMessage> context) {

    /**
     * @param body     the text, also when a moderator has hidden it: the moderator is the one
     *                 person who needs to read it
     * @param reported whether this is the message the report is about
     */
    public record ContextMessage(UUID id, UUID senderId, String body, boolean hidden,
                                 boolean reported, Instant sentAt) {
    }
}
