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

    /** Login throttled. Carries {@code retryAfterSeconds} and a {@code Retry-After} header. */
    TOO_MANY_ATTEMPTS,

    FORBIDDEN,
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    UNSUPPORTED_MEDIA_TYPE,
    CONFLICT,

    /** The seller already has as many live listings as their account allows. */
    ACTIVE_LISTING_LIMIT_REACHED,

    /** Someone else changed the record first; the client should reload and retry. */
    CONCURRENT_MODIFICATION,

    /** The file is not a format we can decode and resize. */
    UNSUPPORTED_IMAGE_TYPE,
    IMAGE_TOO_LARGE,
    /** Below the minimum usable resolution for a vehicle photo. */
    IMAGE_TOO_SMALL,
    /** The listing already holds as many photos as it may. */
    TOO_MANY_IMAGES,
    /** Confirmation arrived but storage holds nothing at that key. */
    UPLOAD_NOT_FOUND,

    INTERNAL_ERROR
}
