package com.buildrisk.radar.adapters.dart;

/** 키 미등록·권한·필드 오류처럼 재시도로 해결되지 않는 DART 오류. */
public class DartApiException extends RuntimeException {
    private final String status;

    public DartApiException(String status, String message) {
        super("DART " + status + ": " + message);
        this.status = status;
    }

    public String status() { return status; }
}
