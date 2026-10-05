package com.autox.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Symmetric (HS256) JWT signing.
 *
 * <p>Symmetric is the right choice while the API is the only issuer and the only
 * verifier. If a second service ever has to verify tokens without being able to
 * mint them, this becomes RSA/EC with a published JWKS — the rest of the code is
 * unaffected, since everything goes through {@link JwtEncoder}/{@link JwtDecoder}.
 */
@Configuration
public class JwtConfig {

    /** HS256 requires a key at least as long as the hash output. */
    private static final int MIN_KEY_BYTES = 32;

    @Bean
    SecretKey jwtSigningKey(AuthProperties properties) {
        byte[] keyBytes = decode(properties.jwtSecret());
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "AUTOX_JWT_SECRET must be at least " + MIN_KEY_BYTES + " bytes (got " + keyBytes.length
                            + "). Generate one with: openssl rand -base64 48");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Rejects a token minted by another environment even if it leaks the key.
        decoder.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators
                .createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /** Accepts either a base64 secret or raw text, so operators are not forced to encode. */
    private static byte[] decode(String secret) {
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException notBase64) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }
}
