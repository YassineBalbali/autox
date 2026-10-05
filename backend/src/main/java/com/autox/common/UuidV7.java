package com.autox.common;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generator for RFC 9562 version 7 UUIDs: a 48-bit Unix millisecond timestamp
 * followed by random bits, so values sort by creation time and keep B-tree
 * inserts local without exposing a guessable sequence.
 *
 * <p>Written explicitly rather than delegating to a Hibernate generator so the
 * layout is auditable and identical on every write path (JPA, batch jobs, tests).
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        return generate(System.currentTimeMillis());
    }

    static UUID generate(long unixMillis) {
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        long msb = (unixMillis & 0xFFFFFFFFFFFFL) << 16;
        // 4-bit version (7) + 12 bits of rand_a
        msb |= 0x7000L;
        msb |= ((long) random[0] & 0x0F) << 8;
        msb |= (long) random[1] & 0xFF;

        // 2-bit IETF variant (0b10) + 62 bits of rand_b
        long lsb = 0x8000000000000000L;
        lsb |= ((long) random[2] & 0x3F) << 56;
        for (int i = 3; i < 10; i++) {
            lsb |= ((long) random[i] & 0xFF) << (8 * (9 - i));
        }

        return new UUID(msb, lsb);
    }
}
