package com.autox.savedsearch;

import com.autox.config.AuthProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * The token in an e-mail's unsubscribe link: a search's id, signed. It needs no sign-in and nothing
 * stored, and it does not expire, since an old e-mail's link still has to work.
 *
 * <p>The key is derived from the JWT secret under its own label, so a token here can never be taken
 * for anything else. Rotating that secret therefore ends the links in e-mails already sent: anyone
 * clicking one is told so and can pause the search from "Mes recherches".
 */
@Component
public class UnsubscribeToken {

    private static final String ALGORITHM = "HmacSHA256";
    private static final byte[] LABEL = "autox.saved-search.unsubscribe.v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec key;

    public UnsubscribeToken(AuthProperties auth) {
        Mac derive = mac(new SecretKeySpec(auth.jwtSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
        this.key = new SecretKeySpec(derive.doFinal(LABEL), ALGORITHM);
    }

    public String sign(UUID savedSearchId) {
        byte[] id = bytes(savedSearchId);
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString(id) + "." + base64.encodeToString(mac(key).doFinal(id));
    }

    /** The search this token was signed for, or empty if it was not signed here. */
    public Optional<UUID> verify(String token) {
        int dot = token == null ? -1 : token.indexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        try {
            Base64.Decoder base64 = Base64.getUrlDecoder();
            byte[] id = base64.decode(token.substring(0, dot));
            byte[] signature = base64.decode(token.substring(dot + 1));
            if (id.length != 16 || !MessageDigest.isEqual(mac(key).doFinal(id), signature)) {
                return Optional.empty();
            }
            ByteBuffer buffer = ByteBuffer.wrap(id);
            return Optional.of(new UUID(buffer.getLong(), buffer.getLong()));
        } catch (IllegalArgumentException notBase64) {
            return Optional.empty();
        }
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static Mac mac(SecretKeySpec key) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac;
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is not available", impossible);
        }
    }
}
