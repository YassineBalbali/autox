package com.autox.auth.dto;

import com.autox.user.dto.UserSummary;
import java.time.Instant;

/**
 * What login and refresh return.
 *
 * <p>{@code expiresInSeconds} is included alongside the absolute instant so a client
 * with a skewed clock can still schedule its refresh correctly.
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        UserSummary user) {

    public static AuthResponse of(String accessToken, long expiresInSeconds, Instant accessTokenExpiresAt,
                                  String refreshToken, Instant refreshTokenExpiresAt, UserSummary user) {
        return new AuthResponse(accessToken, "Bearer", expiresInSeconds, accessTokenExpiresAt,
                refreshToken, refreshTokenExpiresAt, user);
    }
}
