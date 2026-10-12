package com.autox.auth.dto;

import com.autox.user.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
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
        @Size(max = 160) String companyName,

        /**
         * The company registry identifier. Required when {@code role} is PROFESSIONNEL,
         * ignored otherwise.
         *
         * <p>Free text with a length cap, and deliberately not validated as a SIRET. The
         * column behind it is country-specific — FR: SIRET, DE:
         * Handelsregisternummer — and hard-coding a French checksum into the API would
         * make the second market a schema change. A client knows the country at the point
         * of entry and can check the shape there.
         */
        @Size(max = 40) String registrationNumber) {

    @Schema(hidden = true)
    @AssertTrue(message = "companyName is required when registering as PROFESSIONNEL")
    public boolean isCompanyNamePresentForPro() {
        return role != UserRole.PROFESSIONNEL || (companyName != null && !companyName.isBlank());
    }

    @Schema(hidden = true)
    @AssertTrue(message = "registrationNumber is required when registering as PROFESSIONNEL")
    public boolean isRegistrationNumberPresentForPro() {
        return role != UserRole.PROFESSIONNEL
                || (registrationNumber != null && !registrationNumber.isBlank());
    }

    @Schema(hidden = true)
    @AssertTrue(message = "role must be PARTICULIER or PROFESSIONNEL")
    public boolean isSelfAssignableRole() {
        return role != UserRole.ADMIN;
    }
}
