package com.autox.user.dto;

import com.autox.user.AppUser;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import java.util.UUID;

/** The compact user shape embedded in auth responses. */
public record UserSummary(
        UUID id,
        String email,
        UserRole role,
        UserStatus status,
        String displayName,
        boolean emailVerified,
        String countryCode,
        String locale) {

    public static UserSummary from(AppUser user) {
        return new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getDisplayName(),
                user.isEmailVerified(),
                user.getCountry().getCode(),
                user.getLocale());
    }
}
