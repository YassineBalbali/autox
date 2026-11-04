package com.autox.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Spends a verification link.
 *
 * @param token the value from the emailed link's {@code token} parameter
 */
public record VerifyEmailRequest(

        @NotBlank @Size(max = 200)
        String token) {
}
