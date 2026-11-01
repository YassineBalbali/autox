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
    REGISTRATION_IP,

    /** Messages sent by one account. Keyed by user id: sending needs an account. */
    MESSAGE_SEND,

    /**
     * Conversations started by one account. What a spammer writing to every seller has to
     * do, and what a genuine buyer does a few times a day, so it gets its own, lower cap.
     */
    CONVERSATION_START,

    /**
     * Natural-language search requests from one client address. Its own scope, and far below
     * {@link #PUBLIC_READ}: each one spends a shared language-model quota, not just a query.
     */
    SEARCH_INTERPRET
}
