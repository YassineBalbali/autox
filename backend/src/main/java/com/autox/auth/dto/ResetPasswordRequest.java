package com.autox.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Spends a reset link.
 *
 * @param token       the value from the emailed link's {@code token} parameter
 * @param newPassword same length rule as registration
 */
public record ResetPasswordRequest(

        @NotBlank @Size(max = 200)
        String token,

        @NotBlank @Size(min = 12, max = 128, message = "must be between 12 and 128 characters")
        String newPassword) {
}
