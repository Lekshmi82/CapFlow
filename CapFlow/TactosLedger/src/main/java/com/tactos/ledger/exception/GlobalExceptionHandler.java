package com.tactos.ledger.exception;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Centralized, global exception handler for the TactosLedger API.
 *
 * <p>This class intercepts all unhandled exceptions thrown by {@code @RestController}
 * methods and translates them into structured, sanitized HTTP error responses.
 * This decouples error-handling logic from business logic (SRP), ensures consistent
 * error payloads across the entire API surface, and prevents accidental leakage of
 * internal stack traces or database error messages to the React frontend.
 *
 * <h2>Design Principles Applied</h2>
 * <ul>
 *   <li><strong>SRP</strong>: Exception handling is fully isolated from business logic.</li>
 *   <li><strong>OCP</strong>: New exception types can be handled by adding a method
 *       without modifying existing handlers.</li>
 *   <li><strong>Information Security</strong>: All error responses are sanitized.
 *       Internal details (stack traces, MongoDB field names, query structures) are
 *       logged server-side but never exposed to the client.</li>
 * </ul>
 *
 * @author TactosLedger Engineering
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles concurrent modification conflicts detected by Spring Data's
     * Optimistic Locking mechanism.
     *
     * <p>This is triggered when a stale {@link StartupRound} document (one where
     * the in-memory {@code @Version} value no longer matches the persisted value)
     * is passed to a save operation, indicating that another thread mutated the
     * document between our read and write.
     *
     * <p>HTTP 409 Conflict is semantically correct here: the client's request
     * conflicted with the current state of the resource due to concurrent access.
     * The client should retry with a fresh read.
     *
     * @param ex      the optimistic locking failure
     * @param request the originating web request
     * @return a 409 Conflict response with a detailed, actionable error body
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLockingFailure(
            OptimisticLockingFailureException ex,
            WebRequest request) {

        log.error("CONCURRENCY_CONFLICT [409] — Optimistic locking failure on path [{}]. " +
                  "A stale document version was detected. Client should retry the request. " +
                  "Detail: {}", request.getDescription(false), ex.getMessage());

        ApiErrorResponse errorResponse = ApiErrorResponse.builder()
                .status(HttpStatus.CONFLICT.value())
                .error("CONCURRENCY_CONFLICT")
                .message("Your request conflicted with a concurrent modification to the same resource. " +
                         "This is a transient error. Please refresh and retry your operation.")
                .path(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .resolution("Retry the request. If this error persists, contact support with the provided timestamp.")
                .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    /**
     * Handles the business case where a round has insufficient remaining allocation
     * to fulfil the requested amount.
     *
     * <p>This is NOT a server error — it is an expected business outcome in a
     * high-concurrency environment (two requests raced; one lost). HTTP 409 is
     * appropriate as it signals a state conflict, not a bad client request.
     *
     * @param ex      the over-subscription business exception
     * @param request the originating web request
     * @return a 409 Conflict response with a clear explanation for the ops manager UI
     */
    @ExceptionHandler(AllocationOversubscribedException.class)
    public ResponseEntity<ApiErrorResponse> handleAllocationOversubscribed(
            AllocationOversubscribedException ex,
            WebRequest request) {

        log.warn("ALLOCATION_REJECTED [409] — Round over-subscription attempt blocked on path [{}]. " +
                 "Detail: {}", request.getDescription(false), ex.getMessage());

        ApiErrorResponse errorResponse = ApiErrorResponse.builder()
                .status(HttpStatus.CONFLICT.value())
                .error("ALLOCATION_REJECTED")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .resolution("This round is no longer accepting allocations. Please verify the round status in the dashboard.")
                .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    /**
     * Handles requests targeting a funding round that does not exist in the database.
     *
     * <p>HTTP 404 Not Found is the correct semantic response for a resource that
     * cannot be located by its identifier.
     *
     * @param ex      the not-found exception
     * @param request the originating web request
     * @return a 404 Not Found response
     */
    @ExceptionHandler(RoundNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleRoundNotFound(
            RoundNotFoundException ex,
            WebRequest request) {

        log.warn("ROUND_NOT_FOUND [404] — Request for non-existent round on path [{}]. " +
                 "Detail: {}", request.getDescription(false), ex.getMessage());

        ApiErrorResponse errorResponse = ApiErrorResponse.builder()
                .status(HttpStatus.NOT_FOUND.value())
                .error("ROUND_NOT_FOUND")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .resolution("Verify the round ID is correct. Use GET /api/v1/rounds to list all available rounds.")
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    /**
     * Handles bad input arguments passed directly by the service layer
     * (e.g., null amount, zero amount, blank investor name).
     *
     * <p>HTTP 400 Bad Request indicates the client sent a semantically invalid payload.
     *
     * @param ex      the illegal argument exception
     * @param request the originating web request
     * @return a 400 Bad Request response with the specific validation failure
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            WebRequest request) {

        log.warn("INVALID_REQUEST [400] — Validation failure on path [{}]. Detail: {}",
                request.getDescription(false), ex.getMessage());

        ApiErrorResponse errorResponse = ApiErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error("INVALID_REQUEST")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .resolution("Review the request payload. Ensure all required fields are present and valid.")
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles Bean Validation ({@code @Valid}) failures on request DTOs.
     *
     * <p>Aggregates all field-level validation errors from the
     * {@link MethodArgumentNotValidException} into a structured list, providing
     * the React frontend with precise, field-specific error messages to display
     * inline in the allocation form.
     *
     * @param ex      the validation exception containing all constraint violations
     * @param request the originating web request
     * @return a 400 Bad Request response with a list of per-field validation errors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationErrors(
            MethodArgumentNotValidException ex,
            WebRequest request) {

        List<String> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fieldError -> String.format("Field '%s': %s (rejected value: '%s')",
                        fieldError.getField(),
                        fieldError.getDefaultMessage(),
                        fieldError.getRejectedValue()))
                .collect(Collectors.toList());

        log.warn("VALIDATION_FAILURE [400] — {} constraint violation(s) on path [{}]. Errors: {}",
                fieldErrors.size(), request.getDescription(false), fieldErrors);

        ApiErrorResponse errorResponse = ApiErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error("VALIDATION_FAILURE")
                .message(String.format("%d validation error(s) found in the request payload.", fieldErrors.size()))
                .fieldErrors(fieldErrors)
                .path(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .resolution("Correct the indicated fields and resubmit the request.")
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Catch-all handler for any unclassified, unexpected exceptions.
     *
     * <p>This is the last line of defense. It logs the full exception server-side
     * (for debugging) but returns a deliberately vague error message to the client,
     * preventing accidental exposure of internal implementation details, stack traces,
     * or database error messages — a critical security requirement for financial systems.
     *
     * @param ex      the unexpected exception
     * @param request the originating web request
     * @return a 500 Internal Server Error response with a sanitized message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(
            Exception ex,
            WebRequest request) {

        // Log the full exception with stack trace for internal debugging.
        log.error("INTERNAL_ERROR [500] — Unexpected exception on path [{}]. " +
                  "Exception type: [{}]", request.getDescription(false),
                ex.getClass().getName(), ex);

        ApiErrorResponse errorResponse = ApiErrorResponse.builder()
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("INTERNAL_SERVER_ERROR")
                .message("An unexpected error occurred while processing your request. " +
                         "The engineering team has been notified. Please reference the timestamp when reporting this issue.")
                .path(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .resolution("If this error persists, contact support with the error timestamp.")
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }

    // -------------------------------------------------------------------------
    // Inner class: Structured API Error Response DTO
    // -------------------------------------------------------------------------

    /**
     * Immutable, structured error response payload returned to API clients.
     *
     * <p>This schema is consistent across all error types, making it trivial
     * for the React frontend to parse and display error details without
     * conditional branching on response shape.
     *
     * <p>Example JSON output for a 409 Conflict:
     * <pre>
     * {
     *   "status": 409,
     *   "error": "CONCURRENCY_CONFLICT",
     *   "message": "Your request conflicted with a concurrent modification...",
     *   "resolution": "Retry the request...",
     *   "path": "/api/v1/rounds/abc123/allocate",
     *   "timestamp": "2024-07-15T10:30:00.123Z",
     *   "fieldErrors": null
     * }
     * </pre>
     */
    @Getter
    @Builder
    public static class ApiErrorResponse {

        /** The HTTP status code as an integer (mirrors the HTTP response status). */
        private final int status;

        /**
         * A machine-readable error code for programmatic handling by the frontend.
         * Always in UPPER_SNAKE_CASE for reliable client-side switch/case matching.
         */
        private final String error;

        /** A human-readable, actionable description of what went wrong. */
        private final String message;

        /**
         * A suggested resolution step for the end user or operator.
         * Designed to be displayed directly in the UI without modification.
         */
        private final String resolution;

        /** The API path that triggered the error. */
        private final String path;

        /** UTC timestamp of when the error occurred. Useful for log correlation. */
        private final Instant timestamp;

        /**
         * Field-level validation errors. Only populated for VALIDATION_FAILURE responses.
         * Null for all other error types to keep the payload clean.
         */
        private final List<String> fieldErrors;
    }
}
