package com.autox.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 as lower-case hex.
 *
 * <p>Used wherever a value must be stored without being recoverable but is already
 * full-entropy random — refresh tokens, password reset tokens — and wherever a key must
 * not double as a register of what it was made from, as the throttle counters' email keys
 * do. Not for passwords: those are BCrypt, because a password is guessable and the whole
 * point there is to be slow.
 *
 * <p>Centralised because four copies of the same six lines had accumulated, each with its
 * own {@code catch} for an exception the JDK guarantees cannot happen.
 */
public final class Sha256 {

    private Sha256() {
    }

    public static String hex(String value) {
        return hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }
}
