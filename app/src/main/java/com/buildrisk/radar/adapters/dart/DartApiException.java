package com.buildrisk.radar.adapters.dart;

import java.io.Serial;

/** 키 미등록·권한·필드 오류처럼 재시도로 해결되지 않는 DART 오류. */
public class DartApiException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;
    private final String status;

    public DartApiException(String status, String message) {
        super("DART " + status + ": " + message);
        this.status = status;
    }

    public String status() { return status; }
}
