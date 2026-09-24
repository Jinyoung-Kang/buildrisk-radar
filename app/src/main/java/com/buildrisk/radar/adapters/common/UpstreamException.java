package com.buildrisk.radar.adapters.common;

import com.buildrisk.radar.common.KeyMasker;

/** 외부 API 네트워크·5xx·점검 등 일시 오류 — 재시도 대상. */
public class UpstreamException extends RuntimeException {
    private final String provider;

    public UpstreamException(String provider, String message) {
        super(provider + ": " + KeyMasker.mask(message));
        this.provider = provider;
    }

    public UpstreamException(String provider, String message, Throwable cause) {
        super(provider + ": " + KeyMasker.mask(message), cause);
        this.provider = provider;
    }

    public String provider() { return provider; }
}
