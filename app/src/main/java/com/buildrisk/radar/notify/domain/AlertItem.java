package com.buildrisk.radar.notify.domain;

/** 알림 · 웹훅에 싣는 경보 한 건 */
public record AlertItem(long alertId, String kind, String ruleCode, String severity, String title, String targetType,
                        String targetKey, String targetName, String asOf) {}
