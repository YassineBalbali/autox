package com.autox.auth.dto;

import com.autox.user.UserRole;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration payload.
 *
 * <p>{@code role} accepts PARTICULIER or PROFESSIONNEL only — ADMIN is created out
 * of band and can never be self-assigned through this endpoint.
 */
public record RegisterRequest(

        @NotBlank @Email @Size(max = 255)
        String email,

        /**
         * Length is the only rule. Composition rules (one digit, one symbol) push
         * users towards predictable patterns and are no longer recommended practice;
         * 12 characters of anything beats 8 characters of theatre.
         */
        @NotBlank @Size(min = 12, max = 128, message = "must be between 12 and 128 characters")
        String password,

        @NotNull(message = "must be PARTICULIER or PROFESSIONNEL")
        UserRole role,

        @NotBlank @Size(max = 120)
        String displayName,

        @Size(max = 80) String firstName,
        @Size(max = 80) String lastName,

        @Pattern(regexp = "^\\+[1-9][0-9]{6,18}$", message = "must be in international format, e.g. +33612345678")
        String phone,

        @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a 2-letter ISO country code")
        String countryCode,

        @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$", message = "must be a BCP 47 tag, e.g. fr-FR")
        String locale,

        /** Required when {@code role} is PROFESSIONNEL, ignored otherwise. */
        @Size(max = 160) String companyName) {

    @AssertTrue(message = "companyName is required when registering as PROFESSIONNEL")
    public boolean isCompanyNamePresentForPro() {
        return role != UserRole.PROFESSIONNEL || (companyName != null && !companyName.isBlank());
    }

    @AssertTrue(message = "role must be PARTICULIER or PROFESSIONNEL")
    public boolean isSelfAssignableRole() {
        return role != UserRole.ADMIN;
    }
}
