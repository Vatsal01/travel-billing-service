package com.example.billing.web;

import com.example.billing.common.ApiException;
import com.example.billing.common.ErrorCode;
import com.example.billing.common.WireNames;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException e) {
        return problem(e.code(), e.getMessage(), e.properties());
    }

    // Request body failed bean validation: report every offending field, not just the first. 
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBodyValidation(MethodArgumentNotValidException e) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::describe)
                .sorted(Comparator.naturalOrder())
                .toList();
        return problem(ErrorCode.VALIDATION_FAILED, "Request body is not valid", Map.of("errors", errors));
    }

    // Query or path parameter outside its allowed range, for example {limit=1000}.
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleParameterValidation(HandlerMethodValidationException e) {
        List<String> errors = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> result.getMethodParameter().getParameterName()
                                + ": " + error.getDefaultMessage()))
                .sorted(Comparator.naturalOrder())
                .toList();
        return problem(ErrorCode.VALIDATION_FAILED, "Request parameters are not valid",
                Map.of("errors", errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException e) {
        List<String> errors = e.getConstraintViolations().stream()
                .map(ApiExceptionHandler::describe)
                .sorted(Comparator.naturalOrder())
                .toList();
        return problem(ErrorCode.VALIDATION_FAILED, "Request is not valid", Map.of("errors", errors));
    }

  
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException e) {
        return problem(ErrorCode.MALFORMED_REQUEST, rejectionReason(e), Map.of());
    }

    
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("Database constraint rejected a write", e);
        return problem(ErrorCode.CONSTRAINT_VIOLATION,
                "The write conflicts with the current state of the data; re-read and retry", Map.of());
    }

    
    @ExceptionHandler({PessimisticLockingFailureException.class, QueryTimeoutException.class})
    public ResponseEntity<ProblemDetail> handleLockTimeout(DataAccessException e) {
        log.warn("Timed out waiting for a row lock", e);
        return problem(ErrorCode.LOCK_TIMEOUT,
                "Another request is writing this resource; nothing was changed, retry shortly",
                Map.of());
    }

   
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> handleDataAccessFailure(DataAccessException e) {
        log.error("Unhandled data access failure", e);
        return problem(ErrorCode.DATA_ACCESS_FAILURE, "The request could not be completed", Map.of());
    }

    private static ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail,
                                                         Map<String, Object> properties) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.status().getReasonPhrase());
        problem.setProperty("code", code.name());
        properties.forEach(problem::setProperty);
        return ResponseEntity.status(code.status()).body(problem);
    }

    private static String describe(FieldError error) {
        return WireNames.snakeCase(error.getField()) + ": " + error.getDefaultMessage();
    }

    private static String describe(ConstraintViolation<?> violation) {
        return WireNames.snakeCase(violation.getPropertyPath().toString()) + ": " + violation.getMessage();
    }

    
    private static String rejectionReason(HttpMessageNotReadableException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof IllegalArgumentException && cause.getMessage() != null) {
                return cause.getMessage();
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return "Request body could not be read: malformed JSON, or a value of the wrong type";
    }
}
