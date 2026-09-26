package io.jobplatform.api;

import io.jobplatform.jobs.JobConflictException;
import io.jobplatform.jobs.JobNotFoundException;
import io.jobplatform.jobs.JobValidationException;
import io.jobplatform.identity.IdentityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(JobValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError validation(JobValidationException exception, HttpServletRequest request) {
        return error("VALIDATION_ERROR", exception.getMessage(), request);
    }

    @ExceptionHandler(JobConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError conflict(JobConflictException exception, HttpServletRequest request) {
        return error(exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(JobNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError notFound(JobNotFoundException exception, HttpServletRequest request) {
        return error("NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(IdentityService.InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiError unauthorized(IdentityService.InvalidCredentialsException exception, HttpServletRequest request) {
        return error("INVALID_CREDENTIALS", "Invalid or expired credentials.", request);
    }

    private ApiError error(String code, String message, HttpServletRequest request) {
        return new ApiError(code, message, CorrelationIdFilter.value(request));
    }
}
