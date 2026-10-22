package com.autox.messaging.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One message. Plain text: it is shown as typed, never as markup. */
public record SendMessageRequest(
        @NotBlank @Size(max = 2000) String body) {
}
