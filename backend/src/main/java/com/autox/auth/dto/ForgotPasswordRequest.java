package com.autox.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Asks for a reset link.
 *
 * <p>The address is validated for shape only. Whether it belongs to an account is not
 * reported back, by design — see {@code PasswordResetService}.
 */
public record ForgotPasswordRequest(

        @NotBlank @Email @Size(max = 255)
        String email) {
}
