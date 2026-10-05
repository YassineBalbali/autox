package com.autox.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The refresh token travels in the body, not in a cookie: a native app has no
 * cookie jar, and the same contract has to serve both clients.
 */
public record RefreshRequest(

        @NotBlank @Size(max = 512)
        String refreshToken) {
}
