package com.autox.api.error;

import org.springframework.http.HttpStatus;

/**
 * Every expected failure in the application layer. Carries the HTTP status and the
 * {@link ErrorCode} with it, so {@link ApiExceptionHandler} needs no mapping table
 * and no exception class per case.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final ErrorCode code;
    private final Long retryAfterSeconds;

    public ApiException(HttpStatus status, ErrorCode code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, ErrorCode code, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ErrorCode getCode() {
        return code;
    }

    /** Non-null only for throttled responses; drives the {@code Retry-After} header. */
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    // --- Authentication ---------------------------------------------------

    public static ApiException emailAlreadyRegistered() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.EMAIL_ALREADY_REGISTERED,
                "An account already exists for this email address.");
    }

    /**
     * Deliberately identical whether the email is unknown or the password is wrong:
     * distinguishing them turns the login endpoint into an account-enumeration oracle.
     */
    public static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS,
                "Email address or password is incorrect.");
    }

    public static ApiException accountSuspended() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCode.ACCOUNT_SUSPENDED,
                "This account is suspended. Contact support.");
    }

    public static ApiException accountClosed() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCode.ACCOUNT_CLOSED,
                "This account has been closed.");
    }

    public static ApiException invalidRefreshToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_REFRESH_TOKEN,
                "Refresh token is invalid, expired or has already been used.");
    }

    /**
     * Too many recent failures for this account key or this address.
     *
     * <p>429 rather than a repeated 401, because a client needs to know to back off
     * instead of retrying. It reveals nothing about whether the account exists: the
     * counter is keyed on the submitted address whether or not one is registered.
     */
    public static ApiException tooManyLoginAttempts(long retryAfterSeconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_ATTEMPTS,
                "Too many failed sign-in attempts. Try again in " + retryAfterSeconds + " seconds.",
                retryAfterSeconds);
    }

    /**
     * Too many public read requests from one address. 429 with {@code Retry-After}, so a
     * well-behaved client backs off for exactly as long as it needs to rather than hammering.
     */
    public static ApiException rateLimited(long retryAfterSeconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED,
                "Too many requests. Try again in " + retryAfterSeconds + " seconds.",
                retryAfterSeconds);
    }

    /** Natural-language search could not answer; the ordinary search still can. */
    public static ApiException interpreterUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.INTERPRETER_UNAVAILABLE,
                "Natural-language search is unavailable right now. Use the ordinary search.");
    }

    public static ApiException currentPasswordIncorrect() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.CURRENT_PASSWORD_INCORRECT,
                "The current password is incorrect.");
    }

    /**
     * One answer for every way a reset token can fail — unknown, expired, spent, or
     * superseded by a newer request. Distinguishing them would tell someone holding a
     * stolen link which of those it was, and there is nothing a legitimate user does
     * differently in any of the four cases: ask for a new link.
     */
    public static ApiException invalidResetToken() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_RESET_TOKEN,
                "This password reset link is no longer valid. Request a new one.");
    }

    public static ApiException invalidToken(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_TOKEN, detail);
    }

    // --- Generic ----------------------------------------------------------

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, what + " was not found.");
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, detail);
    }

    public static ApiException validationFailed(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, detail);
    }

    /**
     * A limit on the caller's plan is reached. 409 rather than 403: the request is
     * well-formed and the caller is permitted in principle — the account's current state
     * conflicts with it, and freeing a slot makes the same request succeed.
     */
    public static ApiException quotaExceeded(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.ACTIVE_LISTING_LIMIT_REACHED, detail);
    }

    // --- Images -----------------------------------------------------------

    /** 415: the format is the problem, not the request's shape. */
    public static ApiException unsupportedImageType(String detail) {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_IMAGE_TYPE, detail);
    }

    /** 413, the status whose entire purpose is this case. */
    public static ApiException imageTooLarge(String detail) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.IMAGE_TOO_LARGE, detail);
    }

    public static ApiException imageTooSmall(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.IMAGE_TOO_SMALL, detail);
    }

    /** 409: a limit on current state, which deleting a photo resolves. */
    public static ApiException tooManyImages(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.TOO_MANY_IMAGES, detail);
    }

    /**
     * 409 rather than 404: the image record exists, the bytes do not. A 404 would suggest the
     * id was wrong, sending a client to re-request a slot it already has.
     */
    public static ApiException uploadNotFound(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.UPLOAD_NOT_FOUND, detail);
    }

    /**
     * 409: publication blocked by the listing's current state. The code is specific so the
     * frontend can point at the photo step rather than showing a generic failure.
     */
    public static ApiException photoRequired(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.PHOTO_REQUIRED, detail);
    }

    public static ApiException invalidCursor(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_CURSOR, detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, detail);
    }
}
