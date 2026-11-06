package com.sahilprojects.bettingApp.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every error leaves the API in the same shape: a problem document with a short machine-readable {@code code} and a
 * {@code detail} sentence a person can read. The frontend shows {@code detail} as it is.
 *
 * Extending ResponseEntityExceptionHandler covers Spring MVC's own errors (unknown route, wrong method, missing
 * parameter and so on) in that same shape, instead of the servlet container's default page.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException ex) {
        return problem(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException ex,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(f -> errors.putIfAbsent(f.getField(), f.getDefaultMessage()));
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are invalid");
        p.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(p);
    }

    /** Broken JSON, a date that does not parse, or a value that is not one of an enum's names. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(@NonNull HttpMessageNotReadableException ex,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "UNREADABLE_BODY",
                "The request body could not be read. Check that it is valid JSON with the right field types"));
    }

    /** An id in the path that is not a number, or a status filter that is not a known status. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, "BAD_PARAMETER",
                "'" + ex.getValue() + "' is not a valid value for " + ex.getName());
    }

    /** Two tabs changed the same row. The loser reloads and tries again. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "STALE_DATA",
                "This record was changed by someone else. Reload and try again.");
    }

    /** A database rule refused the change. The details stay in the log, not in the response. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        return problem(HttpStatus.CONFLICT, "DATA_RULE", "The change is not allowed by the data rules.");
    }

    /** Thrown by Spring Security's method checks; without this the catch-all below would turn it into a 500. */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to do this");
    }

    /** Anything unexpected: logged in full, answered without internals. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong on our side. Please try again.");
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, @NonNull HttpHeaders headers,
                                                          @NonNull HttpStatusCode statusCode,
                                                          @NonNull WebRequest request) {
        // Spring's own errors arrive here with a ProblemDetail body; give them a code like everything else.
        if (body instanceof ProblemDetail p && p.getProperties() == null) {
            p.setProperty("code", HttpStatus.valueOf(statusCode.value()).name());
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(status.getReasonPhrase());
        p.setProperty("code", code);
        return p;
    }
}
