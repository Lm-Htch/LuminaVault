package com.lumi.vault.handler;

import com.lumi.vault.common.Result;
import com.lumi.vault.exception.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Result<Void>> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(Result.error(exception.status().value(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Map<String, String>>> handleValidationException(MethodArgumentNotValidException exception) {
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            details.putIfAbsent(fieldError.getField(),
                    fieldError.getDefaultMessage() == null ? "参数不合法" : fieldError.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(Result.error(400, "参数校验失败", details));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKeyException(DuplicateKeyException exception) {
        return ResponseEntity.status(409).body(Result.error(409, "数据已存在"));
    }
}
