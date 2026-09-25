package com.buildrisk.radar.adapters.common;

/** 외부 API 일일 호출 상한(서버 응답 또는 설정값) 도달 — Job 을 STOPPED 로 멈추고 다음 실행에서 이어갑니다 (FR-203). */
public class QuotaExceededException extends RuntimeException {
    private final boolean fromServer;

    public QuotaExceededException(String message, boolean fromServer) {
        super(message);
        this.fromServer = fromServer;
    }

    public boolean fromServer() { return fromServer; }
}
