package com.buildrisk.radar.adapters.common;

/** 키 미등록·만료·권한 없음 — 재시도해도 소용없으므로 Job 을 바로 실패시킵니다(원인이 실행 이력에 남음). */
public class ApiKeyRejectedException extends RuntimeException {
    public ApiKeyRejectedException(String provider, String message) {
        super(provider + ": " + message);
    }
}
