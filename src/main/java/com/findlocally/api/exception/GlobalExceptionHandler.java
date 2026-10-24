package com.findlocally.api.exception;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ErrorResponses errors;
    public GlobalExceptionHandler(ErrorResponses errors) { this.errors = errors; }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String detail = status.is5xxServerError() ? "The request could not be completed. Please try again."
                : body instanceof ProblemDetail p ? p.getDetail() : "Check the request and try again.";
        if (ex instanceof ResponseStatusException response) detail = response.getReason() == null ? "The request could not be completed." : response.getReason();
        if (ex instanceof HttpMessageNotReadableException) detail = "Request body is missing or contains invalid JSON.";
        var problem = errors.problem(status, detail, path(request));
        if (ex instanceof MethodArgumentNotValidException invalid) {
            problem.setProperty("errors", invalid.getBindingResult().getFieldErrors().stream()
                    .map(error -> Map.of("field", error.getField(), "message", error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage())).toList());
        }
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> validation(ConstraintViolationException ex, WebRequest request) {
        var problem = errors.problem(HttpStatus.BAD_REQUEST, "One or more request values are invalid.", path(request));
        problem.setProperty("errors", ex.getConstraintViolations().stream()
                .map(value -> Map.of("field", value.getPropertyPath().toString(), "message", value.getMessage())).toList());
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Object> database(DataAccessException ex, WebRequest request) {
        HttpStatus status = ex instanceof DataIntegrityViolationException ? HttpStatus.CONFLICT
                : ex instanceof TransientDataAccessException || ex instanceof DataAccessResourceFailureException
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
        String message = status == HttpStatus.CONFLICT ? "This change conflicts with the current data."
                : status == HttpStatus.SERVICE_UNAVAILABLE ? "The service is temporarily unavailable. Please try again."
                : "The request could not be completed. Please try again.";
        return response(status, message, request, ex);
    }

    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    ResponseEntity<Object> security(Exception ex, WebRequest request) {
        return response(ex instanceof AuthenticationException ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN,
                ex instanceof AuthenticationException ? "Sign in to continue." : "You do not have access to this resource.", request, ex);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed. Please try again.", request, ex);
    }

    private ResponseEntity<Object> response(HttpStatus status, String detail, WebRequest request, Exception ex) {
        var problem = errors.problem(status, detail, path(request));
        if (status.is5xxServerError()) LOG.error("Request {} failed: {}", problem.getProperties().get("traceId"), ex.getClass().getName());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
    private String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : "/";
    }
}
