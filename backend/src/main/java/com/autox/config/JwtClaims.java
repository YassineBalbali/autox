package com.autox.config;

/** Claim names used in AutoX access tokens. */
public final class JwtClaims {

    /** The user's role, as a single string. Mapped to a {@code ROLE_*} authority. */
    public static final String ROLE = "role";

    /** Display name, so a client can render a header without a profile round-trip. */
    public static final String NAME = "name";

    /** Account status at issue time. */
    public static final String STATUS = "status";

    /** Whether the email was verified at issue time. */
    public static final String EMAIL_VERIFIED = "email_verified";

    private JwtClaims() {
    }
}
