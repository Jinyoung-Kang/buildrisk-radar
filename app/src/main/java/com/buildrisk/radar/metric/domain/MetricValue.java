package com.buildrisk.radar.metric.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 계산된 지표 한 값. value 가 null 이면 status 에 사유:
 *   MISSING(계정·통계 없음) · NEG_EQUITY(자본잠식) · ZERO_DENOM(분모 0) · PARTIAL(일부 하위 지역만)
 *   · INCONSISTENT(음수일 수 없는 분모가 음수 — 보고서마다 원천 계정이 달라 누적이 줄어든 경우 등)
 */
public record MetricValue(String targetKey, String period, String metricCode, BigDecimal value, String status,
                          Map<String, Object> components) {
    public static final String OK = "OK";

    public boolean ok() { return OK.equals(status) && value != null; }

    /** 저장하는 소수 자릿수 (기업 · 지역 지표 공통) */
    public static BigDecimal scale(BigDecimal v) { return v.setScale(4, RoundingMode.HALF_UP); }
}
