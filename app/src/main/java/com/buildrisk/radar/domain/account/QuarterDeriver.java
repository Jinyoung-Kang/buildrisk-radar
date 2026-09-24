package com.buildrisk.radar.domain.account;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

/**
 * 누적(CUM) → 분기 단독(QTR) 차분 (FR-205).
 *   Q1 = 1분기 누적 · Q2 = 반기 누적 − Q1 누적 · Q3 = 3분기 누적 − 반기 누적 · Q4 = 연간 − 3분기 누적
 * 직전 누적이 없거나 연결/별도 구분이 다르면 분기값을 만들지 않습니다(혼합 차분 방지).
 */
public final class QuarterDeriver {
    private QuarterDeriver() {}

    public record Cum(BigDecimal amount, String fsDiv) {}

    public static Map<String, BigDecimal> derive(Map<String, Cum> cumByPeriod) {
        Map<String, BigDecimal> out = new TreeMap<>();
        for (var e : cumByPeriod.entrySet()) {
            String pk = e.getKey();
            Cum cur = e.getValue();
            if (cur == null || cur.amount() == null) continue;
            if (PeriodKeys.quarter(pk) == 1) {
                out.put(pk, cur.amount());
                continue;
            }
            Cum prev = cumByPeriod.get(PeriodKeys.previous(pk));
            if (prev == null || prev.amount() == null || !prev.fsDiv().equals(cur.fsDiv())) continue;
            out.put(pk, cur.amount().subtract(prev.amount()));
        }
        return out;
    }
}
