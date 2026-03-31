package com.reactor.asl.consumer.sample;

import com.reactor.asl.core.MethodUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class MailApiExceptionHandler {
    @ExceptionHandler(MailNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleNotFound(MailNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("MAIL_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse("BAD_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(MethodUnavailableException.class)
    ResponseEntity<ApiErrorResponse> handleMethodUnavailable(MethodUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiErrorResponse("METHOD_UNAVAILABLE", exception.getMessage()));
    }
}
