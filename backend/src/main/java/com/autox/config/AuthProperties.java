package com.autox.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Authentication configuration, bound from {@code autox.auth.*}.
 *
 * <p>Validated at startup: a blank secret or an absurd TTL fails the context
 * rather than producing tokens nobody can trust.
 */
@Validated
@ConfigurationProperties(prefix = "autox.auth")
public record AuthProperties(

        /** HS256 signing key. Minimum 32 bytes — checked in {@link JwtConfig}. */
        @NotBlank String jwtSecret,

        @NotBlank String issuer,

        Duration accessTokenTtl,

        Duration refreshTokenTtl,

        boolean rotateRefreshTokens,

        /**
         * BCrypt's cost: 12 when unset, which production leaves it. Settable for the test
         * profile alone, which hashes thousands of passwords where a visitor hashes one: at 12,
         * that was most of the suite's time. 4 is the least BCrypt accepts.
         */
        @Min(4) @Max(31) Integer passwordHashCost) {

    /** What a password is hashed at when nothing says otherwise. */
    public static final int DEFAULT_PASSWORD_HASH_COST = 12;

    public AuthProperties {
        if (passwordHashCost == null) {
            passwordHashCost = DEFAULT_PASSWORD_HASH_COST;
        }
        if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
        if (refreshTokenTtl == null || refreshTokenTtl.isNegative() || refreshTokenTtl.isZero()) {
            refreshTokenTtl = Duration.ofDays(90);
        }
    }
}
