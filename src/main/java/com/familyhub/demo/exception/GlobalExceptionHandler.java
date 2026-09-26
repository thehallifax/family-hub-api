package com.familyhub.demo.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;


@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        log.warn("Resource not found: {} {}, message={}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                request,
                ex.getMessage()
        );
    }

    @ExceptionHandler(UsernameAlreadyExists.class)
    public ResponseEntity<ErrorResponse> handleUsernameAlreadyExist(UsernameAlreadyExists ex, HttpServletRequest request) {
        log.warn("Conflict: {} {}, message={}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                request,
                ex.getMessage()
        );
    }

    @ExceptionHandler(InvalidCredentialException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredential(InvalidCredentialException ex, HttpServletRequest request) {
        log.warn("Authentication failed: {} {}", request.getMethod(), request.getRequestURI());
        return buildErrorResponse(
                HttpStatus.UNAUTHORIZED,
                request,
                ex.getMessage()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception: {} {}", request.getMethod(), request.getRequestURI(), ex);
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                request,
                "Internal Server Error"
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied: {} {}", request.getMethod(), request.getRequestURI());
        return buildErrorResponse(HttpStatus.FORBIDDEN, request, ex.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex, HttpServletRequest request) {
        log.warn("Bad request: {} {}, message={}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, request, ex.getMessage());
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        log.warn("Upload too large: {} {}", servletRequest.getMethod(), servletRequest.getRequestURI());
        ErrorResponse error = new ErrorResponse(HttpStatus.PAYLOAD_TOO_LARGE.value(),
                servletRequest.getRequestURI(), "Choose an image smaller than 12 MiB");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(error);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex, HttpServletRequest request) {
        log.warn("Conflict: {} {}, message={}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, request, ex.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        log.warn("Optimistic lock conflict: {} {}", request.getMethod(), request.getRequestURI());
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                request,
                "The resource was modified concurrently. Please retry."
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation: {} {}, message={}",
                request.getMethod(), request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                request,
                "The request conflicts with the current state of the resource."
        );
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ErrorResponse.ValidationError> errorList = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new ErrorResponse.ValidationError(error.getField(), error.getDefaultMessage()))
                .toList();

        // WebRequest parameter is required by the ResponseEntityExceptionHandler override signature
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();

        log.warn("Validation failed: {} {}, errors={}",
                servletRequest.getMethod(), servletRequest.getRequestURI(), errorList);

        String path = servletRequest.getRequestURI();

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                path,
                "Bad Request",
                errorList
        );

        return ResponseEntity.badRequest().body(errorResponse);
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(HttpStatus status, HttpServletRequest request, String message) {
        ErrorResponse error = new ErrorResponse(status.value(), request.getRequestURI(), message);

        return ResponseEntity.status(status).body(error);
    }
}
