package com.autox.auth;

import com.autox.config.AuthProperties;
import com.autox.config.JwtClaims;
import com.autox.user.AppUser;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Mints short-lived access tokens.
 *
 * <p>The subject is the user's UUID, never the email: the email can change, and an
 * identifier that doubles as contact information ends up in logs. Claims are kept
 * to what an API call needs to authorise without a database round-trip.
 */
@Service
public class AccessTokenService {

    private final JwtEncoder jwtEncoder;
    private final AuthProperties properties;

    public AccessTokenService(JwtEncoder jwtEncoder, AuthProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public AccessToken issue(AppUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(JwtClaims.ROLE, user.getRole().name())
                .claim(JwtClaims.NAME, user.getDisplayName())
                .claim(JwtClaims.STATUS, user.getStatus().name())
                .claim(JwtClaims.EMAIL_VERIFIED, user.isEmailVerified())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new AccessToken(value, expiresAt, properties.accessTokenTtl().toSeconds());
    }

    public record AccessToken(String value, Instant expiresAt, long expiresInSeconds) {
    }
}
