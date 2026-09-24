package com.buildrisk.radar.adapters.common;

/** .env 에 키가 없을 때 — 재시도해도 소용없으므로 Job 을 바로 실패시킵니다. */
public class ApiKeyMissingException extends RuntimeException {
    public ApiKeyMissingException(String envName) {
        super(envName + " 가 .env 에 없습니다. 키를 넣고 docker compose up -d app 으로 다시 띄우세요.");
    }
}
