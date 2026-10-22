package com.autox.messaging;

import io.swagger.v3.oas.annotations.media.Schema;

/** How a moderator closed a report. Mirrors {@code ck_message_report_resolution}. */
@Schema(enumAsRef = true)
public enum MessageReportResolution {

    /** The message was hidden from both sides. */
    HIDDEN,

    /** The message stays; the report needed no action. */
    DISMISSED
}
