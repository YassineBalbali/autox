package com.autox.user.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial profile update. A {@code null} member means "leave unchanged"; a blank
 * string clears an optional field.
 *
 * <p>Email and password are deliberately absent. Changing an email has to
 * re-trigger verification, and changing a password has to revoke every other
 * session — both are their own endpoints, not a field in a profile PATCH.
 */
public record UpdateProfileRequest(

        @Size(min = 1, max = 120) String displayName,
        @Size(max = 80) String firstName,
        @Size(max = 80) String lastName,

        @Pattern(regexp = "^(\\+[1-9][0-9]{6,18})?$",
                message = "must be in international format, e.g. +33612345678, or empty to clear")
        String phone,

        @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$", message = "must be a BCP 47 tag, e.g. fr-FR")
        String locale,

        @Pattern(regexp = "^([A-Z]{3})?$", message = "must be a 3-letter ISO currency code, or empty to clear")
        String preferredCurrencyCode,

        /** Applied only for PROFESSIONNEL accounts; rejected for anyone else. */
        @Valid ProProfileUpdate professional) {

    public record ProProfileUpdate(
            @Size(min = 1, max = 160) String companyName,
            @Size(max = 60) String legalForm,
            @Size(max = 40) String registrationNumber,
            @Size(max = 40) String vatNumber,
            @Size(max = 255) String websiteUrl,
            @Size(max = 5000) String description,
            @Size(max = 160) String addressLine1,
            @Size(max = 160) String addressLine2,

            @Pattern(regexp = "^(\\+[1-9][0-9]{6,18})?$", message = "must be in international format")
            String contactPhone) {
    }
}
