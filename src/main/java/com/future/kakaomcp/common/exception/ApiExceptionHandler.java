package com.future.kakaomcp.common.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ErrorResponse> handleBiz(BizException e) {
        return ResponseEntity.badRequest().body(
                ErrorResponse.builder()
                        .code(e.getCode())
                        .message(e.getMessage())
                        .build()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAny(Exception e) {
        return ResponseEntity.internalServerError().body(
                ErrorResponse.builder()
                        .code("INTERNAL_ERROR")
                        .message(e.getMessage())
                        .build()
        );
    }
}