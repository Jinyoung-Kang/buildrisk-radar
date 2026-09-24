package com.buildrisk.radar.common.error;

import com.buildrisk.radar.common.KeyMasker;
import com.buildrisk.radar.common.web.TraceIdFilter;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException e) {
        return of(e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage).collect(Collectors.joining(" "));
        return of(ErrorCode.VALIDATION_ERROR, msg.isBlank() ? "요청 값이 올바르지 않습니다." : msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorResponse> constraint(ConstraintViolationException e) {
        // "geojson.simplify: 5000 이하여야 합니다" → "simplify: 5000 이하여야 합니다"
        String msg = e.getConstraintViolations().stream().map(v -> {
            String path = v.getPropertyPath().toString();
            return path.substring(path.lastIndexOf('.') + 1) + ": " + v.getMessage();
        }).sorted().collect(Collectors.joining(", "));
        return of(ErrorCode.VALIDATION_ERROR, msg);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
        return of(ErrorCode.VALIDATION_ERROR, "파라미터 '" + e.getName() + "' 의 형식이 올바르지 않습니다: " + e.getValue());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponse> missingParam(MissingServletRequestParameterException e) {
        return of(ErrorCode.VALIDATION_ERROR, "필수 파라미터 '" + e.getParameterName() + "' 가 없습니다.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> illegalArgument(IllegalArgumentException e) {
        return of(ErrorCode.VALIDATION_ERROR, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return of(ErrorCode.VALIDATION_ERROR, "요청 본문(JSON)을 읽을 수 없습니다.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> method(HttpRequestMethodNotSupportedException e) {
        return of(ErrorCode.METHOD_NOT_ALLOWED, e.getMethod() + " 은(는) 이 경로에서 지원하지 않습니다.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> mediaType(HttpMediaTypeNotSupportedException e) {
        return of(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Content-Type 은 application/json 이어야 합니다.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> noResource(NoResourceFoundException e) {
        return of(ErrorCode.NOT_FOUND, "경로를 찾을 수 없습니다.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unknown(Exception e) {
        log.error("처리되지 않은 오류: {}", KeyMasker.mask(e.toString()), e);
        return of(ErrorCode.INTERNAL_ERROR, "서버 오류가 발생했습니다.");
    }

    private static ResponseEntity<ErrorResponse> of(ErrorCode code, String message) {
        return ResponseEntity.status(code.status())
                .body(new ErrorResponse(code.name(), KeyMasker.mask(message), MDC.get(TraceIdFilter.MDC_KEY)));
    }
}
