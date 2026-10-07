package com.autox.auth.throttle;

/** Mirrors ck_login_attempt_scope. */
public enum ThrottleScope {

    /** Keyed by a hash of the submitted email — existing or not, so nothing is revealed. */
    ACCOUNT,

    /** Keyed by the client address. Catches a spray across many accounts. */
    IP
}
