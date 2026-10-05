package com.autox.user;

/** Mirrors ck_refresh_token_reason. */
public enum TokenRevocationReason {
    LOGOUT,
    ROTATED,
    /** A token already rotated was presented again: the whole family is revoked. */
    REUSE_DETECTED,
    PASSWORD_CHANGED,
    ADMIN_REVOKED,
    EXPIRED
}
