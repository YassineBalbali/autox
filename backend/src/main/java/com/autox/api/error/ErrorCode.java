package com.autox.api.error;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Stable, machine-readable error identifiers returned as the {@code code} member of
 * every error response.
 *
 * <p>Clients branch on these, never on the human-readable {@code detail} or on the
 * HTTP status alone — a phone app needs to tell "wrong password" apart from
 * "account suspended", and both are 401.
 */
@Schema(enumAsRef = true)
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

    /**
     * The {@code currentPassword} sent to the change-password endpoint is wrong.
     *
     * <p>400 and its own code rather than 401 {@link #INVALID_CREDENTIALS}: the caller's
     * access token is perfectly valid, and a 401 would send a client off to refresh it and
     * retry instead of telling the user they mistyped.
     */
    CURRENT_PASSWORD_INCORRECT,

    /** Reset token unknown, expired, already used, or replaced by a newer request. */
    INVALID_RESET_TOKEN,

    /** Login throttled. Carries {@code retryAfterSeconds} and a {@code Retry-After} header. */
    TOO_MANY_ATTEMPTS,

    /**
     * Too many requests to a public read endpoint from one address. Distinct from
     * {@link #TOO_MANY_ATTEMPTS} because the remedies differ: that one means "your password
     * is wrong, stop guessing", this one means "slow down". Also carries
     * {@code retryAfterSeconds}.
     */
    RATE_LIMITED,

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
    /** Publication attempted with no confirmed photo. */
    PHOTO_REQUIRED,

    /** The pagination cursor is malformed, or belongs to a different sort order. */
    INVALID_CURSOR,

    INTERNAL_ERROR
}
