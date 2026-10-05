package com.autox.user.dto;

import com.autox.user.AppUser;
import com.autox.user.ProProfile;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * The full own-profile view. The password hash and the internal version counter are
 * not represented here at all, so they cannot leak by accident.
 */
public record UserProfileResponse(
        UUID id,
        String email,
        UserRole role,
        UserStatus status,
        String displayName,
        String firstName,
        String lastName,
        String phone,
        boolean phoneVerified,
        String countryCode,
        String locale,
        String preferredCurrencyCode,
        boolean emailVerified,
        Instant createdAt,
        Instant lastLoginAt,
        ProProfileView professional) {

    public static UserProfileResponse from(AppUser user, ProProfile professional) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getDisplayName(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhoneE164(),
                user.getPhoneVerifiedAt() != null,
                user.getCountry().getCode(),
                user.getLocale(),
                user.getPreferredCurrency() == null ? null : user.getPreferredCurrency().getCode(),
                user.isEmailVerified(),
                user.getCreatedAt(),
                user.getLastLoginAt(),
                professional == null ? null : ProProfileView.from(professional));
    }

    /** Present only for PROFESSIONNEL accounts. */
    public record ProProfileView(
            String companyName,
            String legalForm,
            String registrationNumber,
            String vatNumber,
            String websiteUrl,
            String description,
            String addressLine1,
            String addressLine2,
            String contactPhone) {

        static ProProfileView from(ProProfile profile) {
            return new ProProfileView(
                    profile.getCompanyName(),
                    profile.getLegalForm(),
                    profile.getRegistrationNumber(),
                    profile.getVatNumber(),
                    profile.getWebsiteUrl(),
                    profile.getDescription(),
                    profile.getAddressLine1(),
                    profile.getAddressLine2(),
                    profile.getContactPhoneE164());
        }
    }
}
