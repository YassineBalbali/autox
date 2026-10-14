package com.autox.throttle;

/** Mirrors {@code ck_throttle_scope}. */
public enum ThrottleScope {

    /** Login failures for one submitted email — existing or not, so nothing is revealed. */
    ACCOUNT,

    /** Login failures from one client address. Catches a spray across many accounts. */
    IP,

    /**
     * Requests to the unauthenticated search and reference endpoints, keyed by client
     * address. The only scope that counts successful requests: there is no "failure" to
     * count when the abuse is volume itself.
     */
    PUBLIC_READ,

    /**
     * Password reset requests for one submitted email. Caps how much mail one address can
     * be sent, which matters because the caller need not own the address they type in.
     */
    PASSWORD_RESET,

    /**
     * Password reset requests from one client address, which is what bounds a sweep across
     * many addresses. Kept apart from {@link #IP} so that failing to guess a password and
     * asking for reset links are not counted into the same block.
     */
    PASSWORD_RESET_IP,

    /**
     * Registration attempts from one client address, successful or not. Bounds how many
     * accounts — and how many password hashes — one caller can make the server produce.
     */
    REGISTRATION_IP
}
