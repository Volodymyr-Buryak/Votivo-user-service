package com.votivo.user_service.controller.advice;

import com.votivo.user_service.service.exception.IdentityAccessException;
import com.votivo.user_service.service.exception.ProfileAccessDeniedException;
import com.votivo.user_service.service.exception.ProfileConflictException;
import com.votivo.user_service.service.exception.UserNotFoundException;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail notFound(UserNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "User profile was not found.");
    }

    @ExceptionHandler(ProfileAccessDeniedException.class)
    public ProblemDetail forbidden(ProfileAccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "This profile operation is not permitted.");
    }

    @ExceptionHandler(ProfileConflictException.class)
    public ProblemDetail conflict(ProfileConflictException exception) {
        return problem(HttpStatus.CONFLICT, "The profile update conflicts with an existing account.");
    }

    @ExceptionHandler(IdentityAccessException.class)
    public ProblemDetail unavailable(IdentityAccessException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "The identity service is temporarily unavailable.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception exception) {
        logger.error("Unexpected profile API failure", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.");
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage() == null
                        ? "Invalid value" : error.getDefaultMessage()));
        return super.handleExceptionInternal(exception, validationProblem(errors), headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            return handleExceptionInternal(exception, null, headers, status, request);
        }
        Map<String, String> errors = new TreeMap<>();
        exception.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> errors.putIfAbsent(
                    name == null ? "parameter" : name,
                    error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()));
        });
        return super.handleExceptionInternal(exception, validationProblem(errors), headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception exception,
            @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = status.is5xxServerError() ? "The request could not be completed."
                : "The request is invalid or unsupported.";
        return super.handleExceptionInternal(exception, problem(status, detail), headers, status, request);
    }

    private static ProblemDetail problem(HttpStatusCode status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }

    private static ProblemDetail validationProblem(Map<String, String> errors) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Request validation failed.");
        body.setProperty("errors", errors);
        return body;
    }
}
