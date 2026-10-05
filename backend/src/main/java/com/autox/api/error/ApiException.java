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

    public ApiException(HttpStatus status, ErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ErrorCode getCode() {
        return code;
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

    public static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, detail);
    }
}
