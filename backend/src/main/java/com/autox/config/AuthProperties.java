package com.autox.config;

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

        boolean rotateRefreshTokens) {

    public AuthProperties {
        if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
        if (refreshTokenTtl == null || refreshTokenTtl.isNegative() || refreshTokenTtl.isZero()) {
            refreshTokenTtl = Duration.ofDays(90);
        }
    }
}
