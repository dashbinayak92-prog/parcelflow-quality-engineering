package dev.parcelflow.api.errors;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException exception, HttpServletRequest request) {
        return problem(exception.status(), exception.code(), exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError).toList();
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, errors);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ProblemDetail> handleMalformedRequest(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request is missing or contains an invalid value",
                request, List.of());
    }

    private FieldViolation fieldError(FieldError error) {
        return new FieldViolation(error.getField(), "INVALID_VALUE", error.getDefaultMessage());
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail,
                                                   HttpServletRequest request, List<FieldViolation> errors) {
        var body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("https://parcelflow.dev/problems/" + code.toLowerCase().replace('_', '-')));
        body.setTitle(code.replace('_', ' '));
        body.setProperty("code", code);
        body.setProperty("traceId", request.getHeader("X-Request-Id") == null
                ? UUID.randomUUID().toString() : request.getHeader("X-Request-Id"));
        if (!errors.isEmpty()) body.setProperty("errors", errors);
        return ResponseEntity.status(status).body(body);
    }

    public record FieldViolation(String field, String code, String message) {}
}
