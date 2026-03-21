package com.eureka.agenthub.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
/**
 * 全局异常处理。
 * <p>
 * 将常见异常统一转换成 JSON 错误响应，便于前端展示。
 */
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    /**
     * 处理业务参数异常。
     */
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    /**
     * 处理 Bean Validation 校验异常。
     */
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "request validation failed"));
    }

    @ExceptionHandler(Exception.class)
    /**
     * 处理未知异常，避免异常堆栈直接暴露到调用方。
     */
    public ResponseEntity<Map<String, String>> handleUnknown(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ex.getMessage()));
    }
}
