package com.autox.messaging.dto;

import com.autox.messaging.MessageReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code note} is required when the reason is {@code OTHER}, and optional otherwise. */
public record ReportMessageRequest(
        @NotNull MessageReportReason reason,
        @Size(max = 500) String note) {
}
