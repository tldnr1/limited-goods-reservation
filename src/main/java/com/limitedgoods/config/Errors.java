package com.limitedgoods.config;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.validation.ConstraintViolationException;
@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ApiError.class)
    public ResponseEntity<?> business(ApiError e) {
        var builder = ResponseEntity.status(e.status);
        if (e.status == 429 || e.status == 503) builder.header("Retry-After", "1");
        return builder.body(Map.of("code", e.code));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, MissingRequestHeaderException.class,
        HttpMessageNotReadableException.class, ConstraintViolationException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalid(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST"));
    }
}
