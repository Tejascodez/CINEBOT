package com.cinebot.common.error;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Converts every exception leaving a controller into application/problem+json.
 * Extends ResponseEntityExceptionHandler so Spring MVC's own errors (405, 415, unreadable body...) get the same shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ErrorCode code = ex.code();
        if (code == ErrorCode.INTERNAL_ERROR) {
            log.error("API error {}", code, ex);
        } else if (code.status().is5xxServerError()) {
            // expected operational failures (dependency down / not configured): no stack trace noise
            log.warn("API error {}: {}", code, ex.getMessage());
        } else {
            log.debug("API error {}: {}", code, ex.getMessage());
        }
        ProblemDetail problem = ProblemDetails.of(code, ex.getMessage());
        ex.properties().forEach(problem::setProperty);
        return ResponseEntity.status(code.status()).body(problem);
    }

    // Thrown by method security (@PreAuthorize) inside the MVC layer; must not fall through to the 500 handler.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(ErrorCode.FORBIDDEN.status())
                .body(ProblemDetails.of(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.title()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(ErrorCode.UNAUTHORIZED.status())
                .body(ProblemDetails.of(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.title()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        // Never leak exception messages / stack traces to clients.
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ProblemDetails.of(ErrorCode.INTERNAL_ERROR, "Something went wrong. Reference the traceId when reporting."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException ex,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()))
                .toList();
        ProblemDetail problem = ProblemDetails.of(ErrorCode.VALIDATION_FAILED, "Request has invalid fields");
        problem.setProperty("errors", errors);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(problem);
    }

    /** Decorates Spring MVC's built-in problem bodies with our code + traceId. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(@NonNull Exception ex, @Nullable Object body,
                                                             @NonNull HttpHeaders headers,
                                                             @NonNull HttpStatusCode statusCode,
                                                             @NonNull WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
                problem.setProperty("code", statusCode.is4xxClientError()
                        ? ErrorCode.MALFORMED_REQUEST.name() : ErrorCode.INTERNAL_ERROR.name());
            }
            ProblemDetails.withTraceId(problem);
        }
        return response;
    }
}
