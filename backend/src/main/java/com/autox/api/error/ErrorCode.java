package com.autox.api.error;

/**
 * Stable, machine-readable error identifiers returned as the {@code code} member of
 * every error response.
 *
 * <p>Clients branch on these, never on the human-readable {@code detail} or on the
 * HTTP status alone — a phone app needs to tell "wrong password" apart from
 * "account suspended", and both are 401.
 */
public enum ErrorCode {

    VALIDATION_FAILED,
    MALFORMED_REQUEST,

    EMAIL_ALREADY_REGISTERED,
    INVALID_CREDENTIALS,
    ACCOUNT_SUSPENDED,
    ACCOUNT_CLOSED,

    /** Access token missing, malformed, expired or signed by someone else. */
    INVALID_TOKEN,
    /** Refresh token unknown, expired, already rotated or revoked. */
    INVALID_REFRESH_TOKEN,

    FORBIDDEN,
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    UNSUPPORTED_MEDIA_TYPE,
    CONFLICT,
    INTERNAL_ERROR
}
