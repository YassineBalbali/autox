package com.autox.api.error;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * The error body, <b>for documentation only</b>.
 *
 * <p>Nothing ever instantiates this. Errors are built as Spring {@link
 * org.springframework.http.ProblemDetail} instances with extra members attached through
 * {@code setProperty}, which is the right runtime representation — RFC 9457 is an open
 * shape and the extensions differ per error — but it is invisible to a schema generator.
 * Without this record, the OpenAPI document described 33 operations and not one of their
 * failure modes, so a generated client had no error type and {@link ErrorCode} never
 * reached the spec at all. A client would have to hand-write the one contract it depends
 * on most.
 *
 * <p>Because it is a parallel description rather than the thing itself, it can drift from
 * {@link ApiExceptionHandler}. {@code ErrorContractIntegrationTest} compares the two: every
 * member a real error response carries must be declared here, and every member declared
 * here must be one a real response can carry.
 *
 * @param type      RFC 9457 problem type. Always {@code about:blank}: the {@code code}
 *                  member carries the machine-readable identity instead, because a URI
 *                  nobody dereferences is ceremony.
 * @param title     the HTTP status phrase, e.g. {@code Bad Request}
 * @param status    the HTTP status code, repeated in the body as RFC 9457 requires
 * @param detail    English developer-facing text. <b>Never shown to an end user</b> — it
 *                  is not translated and its wording is not a contract. Clients render
 *                  their own text from {@code code} and, for validation, {@code field}.
 * @param instance  the request path that failed
 * @param code      the stable identifier clients branch on
 * @param timestamp when the error was produced
 * @param errors    one entry per invalid field. Present only for {@code VALIDATION_FAILED}.
 * @param retryAfterSeconds how long to wait. Present only for {@code TOO_MANY_ATTEMPTS}
 *                  and {@code RATE_LIMITED}, and mirrored in the {@code Retry-After} header.
 * @param incidentId correlation id for the log entry. Present only for
 *                  {@code INTERNAL_ERROR}; quote it when reporting a fault.
 */
@Schema(name = "ApiError",
        description = "RFC 9457 problem+json, extended with a stable `code`. Branch on `code`, never on "
                + "`detail` — `detail` is untranslated developer text.")
public record ApiErrorResponse(

        @Schema(example = "about:blank") String type,
        @Schema(example = "Bad Request") String title,
        @Schema(example = "400") int status,
        @Schema(example = "Request validation failed.") String detail,
        @Schema(example = "/api/v1/auth/register") String instance,

        ErrorCode code,
        Instant timestamp,

        List<FieldError> errors,
        @Schema(example = "61") Long retryAfterSeconds,
        String incidentId) {

    /**
     * One invalid field. Flat on purpose: a nested shape is painful to bind to a form on
     * mobile. Mirrors {@link ApiExceptionHandler.FieldViolation}.
     *
     * @param field   the request field's path, e.g. {@code email} or {@code professional.vatNumber}
     * @param message English developer text, for the same reason as {@code detail}. A client
     *                renders its own message from {@code field}.
     */
    @Schema(name = "FieldError")
    public record FieldError(
            @Schema(example = "email") String field,
            @Schema(example = "must be a well-formed email address") String message) {
    }
}
