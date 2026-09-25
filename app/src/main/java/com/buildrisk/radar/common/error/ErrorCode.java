package com.buildrisk.radar.common.error;

import org.springframework.http.HttpStatus;

/** 8-1 공통 규약의 오류 코드. */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    RULE_PARAM_INVALID(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    CSRF_INVALID(HttpStatus.FORBIDDEN),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS),
    COMPANY_NOT_FOUND(HttpStatus.NOT_FOUND),
    REGION_NOT_FOUND(HttpStatus.NOT_FOUND),
    ALERT_NOT_FOUND(HttpStatus.NOT_FOUND),
    RULE_NOT_FOUND(HttpStatus.NOT_FOUND),
    JOB_NOT_FOUND(HttpStatus.NOT_FOUND),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    JOB_ALREADY_RUNNING(HttpStatus.CONFLICT),
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) { this.status = status; }

    public HttpStatus status() { return status; }
}
