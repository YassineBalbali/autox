package com.autox.messaging;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Why a recipient reported a message. Mirrors {@code ck_message_report_reason}. A closed list
 * for the reason moderation reasons are one: a code can be counted, and shown in the
 * moderator's language.
 */
@Schema(enumAsRef = true)
public enum MessageReportReason {

    /** Advance payment, a fake escrow or delivery service, a link to pay through. */
    SCAM,

    /** Insults, threats, harassment. */
    ABUSIVE,

    /** Advertising, or the same message sent to many sellers. */
    SPAM,

    /** Anything else. The only reason that requires a note. */
    OTHER
}
