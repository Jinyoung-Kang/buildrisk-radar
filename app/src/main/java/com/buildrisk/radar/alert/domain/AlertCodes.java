package com.buildrisk.radar.alert.domain;

import java.util.List;

/** 경보 조회 조건이 받는 값 — 캐시 키에 들어가므로 이 값만 받음 (VERIFICATION 105) */
public final class AlertCodes {
    public static final List<String> TARGET_TYPES = List.of("COMPANY", "REGION");
    public static final List<String> SEVERITIES = List.of("HIGH", "MEDIUM", "LOW");
    public static final List<String> STATUSES = List.of("OPEN", "ACK", "CLOSED");

    private AlertCodes() {}
}
