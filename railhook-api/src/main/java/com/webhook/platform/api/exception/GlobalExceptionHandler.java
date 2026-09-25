package com.webhook.platform.api.exception;

import com.webhook.platform.common.security.UrlValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import java.util.Set;

import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        
        String summary = fieldErrors.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", summary);
        
        ErrorResponse error = ErrorResponse.builder()
                .error("validation_error")
                .message("Invalid request parameters")
                .status(HttpStatus.BAD_REQUEST.value())
                .fieldErrors(fieldErrors)
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(
            ResponseStatusException ex) {
        log.warn("Response status exception: {} {}", ex.getStatusCode(), ex.getReason());
        ErrorResponse error = new ErrorResponse(
                ex.getStatusCode().is4xxClientError() ? "client_error" : "server_error",
                ex.getReason() != null ? ex.getReason() : ex.getMessage(),
                ex.getStatusCode().value()
        );
        return ResponseEntity.status(ex.getStatusCode()).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex, WebRequest request) {
        log.error("Bad request: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(
                "invalid_request",
                ex.getMessage(),
                HttpStatus.BAD_REQUEST.value()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedException(
            UnauthorizedException ex, WebRequest request) {
        log.warn("Unauthorized: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(
                "unauthorized",
                ex.getMessage(),
                HttpStatus.UNAUTHORIZED.value()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbiddenException(
            ForbiddenException ex, WebRequest request) {
        log.warn("Forbidden: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(
                ex.getCode(),
                ex.getMessage(),
                HttpStatus.FORBIDDEN.value()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFoundException(
            NotFoundException ex, WebRequest request) {
        log.warn("Not found: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(
                "not_found",
                ex.getMessage(),
                HttpStatus.NOT_FOUND.value()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ErrorResponse> handleQuotaExceededException(
            QuotaExceededException ex, WebRequest request) {
        log.warn("Quota exceeded: {} (plan={})", ex.getQuotaName(), ex.getPlanName());
        Map<String, String> details = new LinkedHashMap<>();
        details.put("quota", ex.getQuotaName());
        details.put("current", String.valueOf(ex.getCurrentUsage()));
        details.put("limit", String.valueOf(ex.getLimit()));
        details.put("plan", ex.getPlanName());
        ErrorResponse error = ErrorResponse.builder()
                .error("quota_exceeded")
                .message(ex.getMessage())
                .status(HttpStatus.PAYMENT_REQUIRED.value())
                .fieldErrors(details)
                .build();
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(error);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflictException(
            ConflictException ex, WebRequest request) {
        log.warn("Conflict: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(
                "conflict",
                ex.getMessage(),
                HttpStatus.CONFLICT.value()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * Usually two requests racing past the service's pre-check. The message carries the SQL, so
     * it stays in the log.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, WebRequest request) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        ErrorResponse error = new ErrorResponse(
                "conflict",
                "The request conflicts with the current state of the resource",
                HttpStatus.CONFLICT.value()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ErrorResponse> handlePropertyReference(
            PropertyReferenceException ex, WebRequest request) {
        log.debug("Unknown property reference: {}", ex.getPropertyName());
        ErrorResponse error = new ErrorResponse(
                "invalid_parameter",
                "Cannot sort by '" + ex.getPropertyName() + "'",
                HttpStatus.BAD_REQUEST.value()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /** Actuator lives on the management port, so /actuator/health on this port lands here. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            NoResourceFoundException ex, WebRequest request) {
        log.debug("No handler for {}", ex.getResourcePath());
        ErrorResponse error = new ErrorResponse(
                "not_found",
                "The requested resource was not found",
                HttpStatus.NOT_FOUND.value()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /** Requests Spring rejects before a controller must not fall to the 500 catch-all, or clients retry them. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, WebRequest request) {
        log.debug("Method {} not supported for this path", ex.getMethod());
        ErrorResponse error = new ErrorResponse(
                "method_not_allowed",
                "The " + ex.getMethod() + " method is not supported for this endpoint",
                HttpStatus.METHOD_NOT_ALLOWED.value()
        );
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        // RFC 9110 requires Allow on a 405.
        Set<HttpMethod> allowed = ex.getSupportedHttpMethods();
        if (allowed != null && !allowed.isEmpty()) {
            response.allow(allowed.toArray(new HttpMethod[0]));
        }
        return response.body(error);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, WebRequest request) {
        // Not ex.getMessage(): Jackson quotes part of the payload, which can hold credentials.
        log.debug("Unreadable request body: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        ErrorResponse error = new ErrorResponse(
                "malformed_request",
                "The request body is missing or is not valid JSON",
                HttpStatus.BAD_REQUEST.value()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, WebRequest request) {
        log.debug("Unsupported content type: {}", ex.getContentType());
        ErrorResponse error = new ErrorResponse(
                "unsupported_media_type",
                "This endpoint accepts application/json",
                HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()
        );
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(error);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException ex, WebRequest request) {
        ErrorResponse error = new ErrorResponse(
                "missing_parameter",
                "Required parameter '" + ex.getParameterName() + "' is missing",
                HttpStatus.BAD_REQUEST.value()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, WebRequest request) {
        ErrorResponse error = new ErrorResponse(
                "invalid_parameter",
                "Parameter '" + ex.getName() + "' is not in the expected format",
                HttpStatus.BAD_REQUEST.value()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(UrlValidator.InvalidUrlException.class)
    public ResponseEntity<ErrorResponse> handleInvalidUrlException(
            UrlValidator.InvalidUrlException ex, WebRequest request) {
        log.warn("Rejected webhook URL: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(
                "invalid_url",
                ex.getMessage(),
                HttpStatus.BAD_REQUEST.value()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntimeException(
            RuntimeException ex, WebRequest request) {
        log.error("Internal server error: {}", ex.getMessage(), ex);
        ErrorResponse error = new ErrorResponse(
                "internal_error",
                "An unexpected error occurred",
                HttpStatus.INTERNAL_SERVER_ERROR.value()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex, WebRequest request) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        ErrorResponse error = new ErrorResponse(
                "internal_error",
                "An unexpected error occurred",
                HttpStatus.INTERNAL_SERVER_ERROR.value()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
