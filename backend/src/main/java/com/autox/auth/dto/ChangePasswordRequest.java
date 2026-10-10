package com.autox.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Password change for a signed-in account.
 *
 * @param currentPassword proof that the caller is the owner and not the holder of a stolen
 *                        access token
 * @param newPassword     same single rule as registration: length, no composition theatre
 * @param deviceLabel     label for the session this call establishes, since the change ends
 *                        every existing one. Omitted, the new session is simply unlabelled.
 */
public record ChangePasswordRequest(

        @NotBlank
        String currentPassword,

        @NotBlank @Size(min = 12, max = 128, message = "must be between 12 and 128 characters")
        String newPassword,

        @Size(max = 120)
        String deviceLabel) {
}
