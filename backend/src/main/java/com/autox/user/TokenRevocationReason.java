package com.autox.user;

/** Mirrors ck_refresh_token_reason. */
public enum TokenRevocationReason {
    LOGOUT,
    ROTATED,
    /** A token already rotated was presented again: the whole family is revoked. */
    REUSE_DETECTED,
    PASSWORD_CHANGED,
    ADMIN_REVOKED,
    EXPIRED,
    /**
     * Ended by the account owner from their own session list. Distinct from
     * {@link #LOGOUT}, which is the device signing itself out — here the device was never
     * asked, and the trail should say so.
     */
    USER_REVOKED
}
