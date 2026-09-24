package com.buildrisk.radar.adapters.dart;

/** 020(요청 제한) 응답 또는 설정한 일일 상한 도달 — Job 을 STOPPED 로 멈추고 다음 실행에서 이어갑니다 (FR-203). */
public class DartQuotaExceededException extends RuntimeException {
    private final boolean fromServer;

    public DartQuotaExceededException(String message, boolean fromServer) {
        super(message);
        this.fromServer = fromServer;
    }

    public boolean fromServer() { return fromServer; }
}
