package com.buildrisk.radar.common.error;

/** {code, message, traceId} — 8-1 오류 형식. */
public record ErrorResponse(String code, String message, String traceId) {}
