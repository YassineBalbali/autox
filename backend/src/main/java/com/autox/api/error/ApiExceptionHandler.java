package com.autox.api.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One error shape for the whole API: RFC 9457 {@code application/problem+json},
 * extended with a stable {@code code}, a {@code timestamp}, and — for validation
 * failures — a flat {@code errors} array a mobile form can bind straight to its fields.
 *
 * <pre>
 * {
 *   "type": "about:blank",
 *   "title": "Bad Request",
 *   "status": 400,
 *   "detail": "Request validation failed.",
 *   "instance": "/api/v1/auth/register",
 *   "code": "VALIDATION_FAILED",
 *   "timestamp": "2026-10-05T12:00:00Z",
 *   "errors": [ { "field": "email", "message": "must be a well-formed email address" } ]
 * }
 * </pre>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException exception, HttpServletRequest request) {
        ProblemDetail problem = problem(exception.getStatus(), exception.getMessage(), exception.getCode(),
                request.getRequestURI());

        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.getStatus());
        Long retryAfter = exception.getRetryAfterSeconds();
        if (retryAfter != null) {
            problem.setProperty("retryAfterSeconds", retryAfter);
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        }
        return response.body(problem);
    }

    /** Bean Validation on a {@code @RequestBody}. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        List<FieldViolation> violations = new ArrayList<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                violations.add(new FieldViolation(error.getField(), message(error))));
        exception.getBindingResult().getGlobalErrors().forEach(error ->
                violations.add(new FieldViolation(error.getObjectName(), error.getDefaultMessage())));
        violations.sort(Comparator.comparing(FieldViolation::field));

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Request validation failed.",
                ErrorCode.VALIDATION_FAILED, path(request));
        problem.setProperty("errors", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    /** Bean Validation on a path variable or query parameter. */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {

        List<FieldViolation> violations = exception.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(violation.getPropertyPath().toString(), violation.getMessage()))
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Request validation failed.",
                ErrorCode.VALIDATION_FAILED, request.getRequestURI());
        problem.setProperty("errors", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    /** Unparseable JSON. The parser message is not echoed back — it leaks internals. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Request body is missing or malformed.",
                ErrorCode.MALFORMED_REQUEST, path(request));
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(
            NoHandlerFoundException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "No endpoint matches this request.",
                ErrorCode.NOT_FOUND, path(request));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * Two writers edited the same row. {@code @Version} on the entity detects it; without
     * this mapping the loser would get a 500, when the right answer is "reload and retry".
     */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(
            org.springframework.dao.OptimisticLockingFailureException exception, HttpServletRequest request) {

        log.debug("Optimistic lock conflict on {} {}", request.getMethod(), request.getRequestURI());
        ProblemDetail problem = problem(HttpStatus.CONFLICT,
                "This record was changed by someone else while you were editing it. Reload and try again.",
                ErrorCode.CONCURRENT_MODIFICATION, request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Anything unexpected. The cause is logged with a correlation id and the client
     * gets that id and nothing else — stack traces and SQL never cross the wire.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        String incidentId = java.util.UUID.randomUUID().toString();
        log.error("Unhandled exception [incident={}] on {} {}", incidentId,
                request.getMethod(), request.getRequestURI(), exception);

        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Quote the incident id when reporting it.",
                ErrorCode.INTERNAL_ERROR, request.getRequestURI());
        problem.setProperty("incidentId", incidentId);
        return ResponseEntity.internalServerError().body(problem);
    }

    static ProblemDetail problem(HttpStatusCode status, String detail, ErrorCode code, String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code.name());
        problem.setProperty("timestamp", Instant.now().toString());
        if (instance != null) {
            problem.setInstance(java.net.URI.create(instance));
        }
        return problem;
    }

    /**
     * The reason a field was refused.
     *
     * <p>A <em>validation</em> failure carries the constraint's own message ("must not be
     * blank"), which was written to be read. A <em>binding</em> failure — "abc" where a
     * number belongs, an enum value that does not exist — carries Spring's conversion
     * message, which names Java classes and echoes the offending value back. That one is
     * replaced: it describes this server's internals, not the caller's mistake.
     */
    private static String message(FieldError error) {
        if (error.isBindingFailure()) {
            return NOT_A_VALID_VALUE;
        }
        return error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage();
    }

    private static final String NOT_A_VALID_VALUE = "is not a valid value for this field";

    /**
     * Every error Spring MVC raises by itself — an unsupported method or content type, a
     * path variable that is not a UUID, a missing parameter — arrives here.
     *
     * <p>Left alone, these were the responses that broke the contract: a problem document
     * with no {@code code} and no {@code timestamp}, and a {@code detail} written by the
     * framework ("Failed to convert 'id' with value: …", "Acceptable representations:
     * [application/json, …]"). A client branching on {@code code} had nothing to branch
     * on, and the text described the server rather than the request.
     *
     * <p>So the framework's body is discarded and ours is built from the status alone. The
     * headers are kept: {@code Allow} on a 405 and {@code Accept} on a 415 are part of
     * HTTP, and say what the caller needs to know without a sentence.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        int value = status.value();
        ErrorCode code;
        String detail;
        if (value >= 500) {
            // The handler above logs unexpected failures with an incident id; these are the
            // framework's own (an async timeout, say) and are logged here for the same reason.
            log.error("Framework error {} on {}", value, path(request), exception);
            code = ErrorCode.INTERNAL_ERROR;
            detail = "An unexpected error occurred.";
        } else if (value == 404) {
            code = ErrorCode.NOT_FOUND;
            detail = "No endpoint matches this request.";
        } else if (value == 405) {
            code = ErrorCode.METHOD_NOT_ALLOWED;
            detail = "This method is not supported on this endpoint.";
        } else if (value == 415 || value == 406) {
            code = ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            detail = value == 415
                    ? "This content type is not supported. Send application/json."
                    : "This response format is not available. Accept application/json.";
        } else if (value == 400) {
            code = ErrorCode.VALIDATION_FAILED;
            detail = "Request validation failed.";
        } else {
            code = ErrorCode.MALFORMED_REQUEST;
            detail = "The request could not be processed.";
        }

        ProblemDetail problem = problem(status, detail, code, path(request));

        // Which parameter, when the framework knows — the name only, never the value or
        // the type it failed to become.
        String field = null;
        if (exception instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException mismatch) {
            field = mismatch.getName();
        } else if (exception instanceof org.springframework.web.bind.MissingServletRequestParameterException missing) {
            field = missing.getParameterName();
        }
        if (field != null) {
            String reason = exception instanceof org.springframework.web.bind.MissingServletRequestParameterException
                    ? "is required" : NOT_A_VALID_VALUE;
            problem.setProperty("errors", List.of(new FieldViolation(field, reason)));
        }

        return new ResponseEntity<>(problem, headers, status);
    }

    private static String path(WebRequest request) {
        return request instanceof org.springframework.web.context.request.ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : null;
    }

    /** One invalid field. Flat on purpose: nested shapes are painful to bind on mobile. */
    public record FieldViolation(String field, String message) {
    }
}
