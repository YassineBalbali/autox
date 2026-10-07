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

    private static String message(FieldError error) {
        return error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage();
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
