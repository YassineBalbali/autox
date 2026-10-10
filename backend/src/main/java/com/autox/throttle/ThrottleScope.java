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
    PUBLIC_READ
}
