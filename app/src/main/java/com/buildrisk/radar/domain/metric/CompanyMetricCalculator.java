package com.buildrisk.radar.domain.metric;

import com.buildrisk.radar.domain.account.PeriodKeys;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 기업 지표 (7-1). 같은 입력이면 같은 결과를 내는 순수 함수 — 재실행 시 같은 값 (FR-501).
 * 분모가 0·음수이거나 계정이 없으면 값 대신 상태 코드를 남깁니다.
 */
public final class CompanyMetricCalculator {
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 한 기간의 표준계정 값. point: BS 시점값 · cum: IS·CF 누적 · qtr: 분기 단독 · sources: 표준계정 → '재무제표:원천 계정명' */
    public record PeriodFacts(String periodKey, String fsDiv, String rceptNo, String reprtCode,
                              Map<String, BigDecimal> point, Map<String, BigDecimal> cum, Map<String, BigDecimal> qtr,
                              Map<String, String> sources) {
        public PeriodFacts(String periodKey, String fsDiv, String rceptNo, String reprtCode, Map<String, BigDecimal> point,
                           Map<String, BigDecimal> cum, Map<String, BigDecimal> qtr) {
            this(periodKey, fsDiv, rceptNo, reprtCode, point, cum, qtr, Map.of());
        }
    }

    private CompanyMetricCalculator() {}

    public static List<MetricValue> compute(String corpCode, Map<String, PeriodFacts> byPeriod) {
        TreeMap<String, PeriodFacts> sorted = new TreeMap<>(byPeriod);
        List<MetricValue> out = new ArrayList<>();
        Map<String, MetricValue> debtRatio = new TreeMap<>();
        Map<String, MetricValue> borrowDep = new TreeMap<>();
        for (PeriodFacts f : sorted.values()) {
            String pk = f.periodKey();
            MetricValue dr = ratio(corpCode, f, "DEBT_RATIO", f.point(), "TOTAL_LIABILITIES", "TOTAL_EQUITY", true, true);
            debtRatio.put(pk, dr);
            out.add(dr);
            out.add(ratio(corpCode, f, "CURRENT_RATIO", f.point(), "CURRENT_ASSETS", "CURRENT_LIABILITIES", true, false));
            MetricValue bd = borrowing(corpCode, f);
            borrowDep.put(pk, bd);
            out.add(bd);
            out.add(ratio(corpCode, f, "INTEREST_COVERAGE", f.qtr(), "OPERATING_INCOME", "INTEREST_EXPENSE", false, false));
            out.add(ratio(corpCode, f, "OCF_MARGIN", f.cum(), "OPERATING_CASH_FLOW", "REVENUE", true, false));
            BigDecimal ocf = f.qtr().get("OPERATING_CASH_FLOW");
            out.add(new MetricValue(corpCode, pk, "OCF_QTR", ocf, ocf == null ? "MISSING" : MetricValue.OK,
                    comp(f, Map.of("operatingCashFlowQtr", nz(ocf)))));
        }
        for (PeriodFacts f : sorted.values()) {
            String pk = f.periodKey();
            PeriodFacts ago = sorted.get(PeriodKeys.yearAgo(pk));
            out.add(diff(corpCode, f, ago, "DEBT_RATIO_YOY", debtRatio));
            out.add(diff(corpCode, f, ago, "BORROWING_DEP_YOY", borrowDep));
            out.add(growth(corpCode, f, ago));
        }
        return out;
    }

    private static MetricValue ratio(String corp, PeriodFacts f, String code, Map<String, BigDecimal> src,
                                     String num, String den, boolean percent, boolean negDenIsEquity) {
        BigDecimal n = src.get(num);
        BigDecimal d = src.get(den);
        Map<String, Object> c = comp(f, Map.of(
                "numerator", Map.of("std", num, "amount", nz(n), "source", f.sources().getOrDefault(num, "-")),
                "denominator", Map.of("std", den, "amount", nz(d), "source", f.sources().getOrDefault(den, "-"))));
        if (n == null || d == null) return new MetricValue(corp, f.periodKey(), code, null, "MISSING", c);
        if (d.signum() == 0) return new MetricValue(corp, f.periodKey(), code, null, "ZERO_DENOM", c);
        if (negDenIsEquity && d.signum() < 0) return new MetricValue(corp, f.periodKey(), code, null, "NEG_EQUITY", c);
        BigDecimal v = n.divide(d, MC);
        if (percent) v = v.multiply(HUNDRED);
        return new MetricValue(corp, f.periodKey(), code, scale(v), MetricValue.OK, c);
    }

    private static MetricValue borrowing(String corp, PeriodFacts f) {
        BigDecimal sb = f.point().get("SHORT_BORROWINGS");
        BigDecimal lb = f.point().get("LONG_BORROWINGS");
        BigDecimal bo = f.point().get("BONDS");
        BigDecimal ta = f.point().get("TOTAL_ASSETS");
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("SHORT_BORROWINGS", nz(sb));
        parts.put("LONG_BORROWINGS", nz(lb));
        parts.put("BONDS", nz(bo));
        parts.put("TOTAL_ASSETS", nz(ta));
        Map<String, Object> c = comp(f, Map.of("components", parts));
        if ((sb == null && lb == null && bo == null) || ta == null) {
            return new MetricValue(corp, f.periodKey(), "BORROWING_DEP", null, "MISSING", c);
        }
        if (ta.signum() <= 0) return new MetricValue(corp, f.periodKey(), "BORROWING_DEP", null, "ZERO_DENOM", c);
        BigDecimal total = zero(sb).add(zero(lb)).add(zero(bo));
        return new MetricValue(corp, f.periodKey(), "BORROWING_DEP", scale(total.divide(ta, MC).multiply(HUNDRED)),
                MetricValue.OK, c);
    }

    private static MetricValue diff(String corp, PeriodFacts f, PeriodFacts ago, String code,
                                    Map<String, MetricValue> base) {
        MetricValue cur = base.get(f.periodKey());
        MetricValue prev = ago == null ? null : base.get(ago.periodKey());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("current", Map.of("periodKey", f.periodKey(), "value", cur != null && cur.ok() ? cur.value() : "-"));
        c.put("yearAgo", Map.of("periodKey", PeriodKeys.yearAgo(f.periodKey()),
                "value", prev != null && prev.ok() ? prev.value() : "-"));
        c.put("fsDiv", f.fsDiv());
        c.put("rceptNo", nz(f.rceptNo()));
        if (cur == null || prev == null || !cur.ok() || !prev.ok()) {
            return new MetricValue(corp, f.periodKey(), code, null, "MISSING", c);
        }
        return new MetricValue(corp, f.periodKey(), code, scale(cur.value().subtract(prev.value())), MetricValue.OK, c);
    }

    private static MetricValue growth(String corp, PeriodFacts f, PeriodFacts ago) {
        BigDecimal cur = f.cum().get("REVENUE");
        BigDecimal prev = ago == null ? null : ago.cum().get("REVENUE");
        Map<String, Object> c = comp(f, Map.of("revenueCum", nz(cur), "revenueCumYearAgo", nz(prev)));
        if (cur == null || prev == null) return new MetricValue(corp, f.periodKey(), "REVENUE_YOY", null, "MISSING", c);
        if (prev.signum() == 0) return new MetricValue(corp, f.periodKey(), "REVENUE_YOY", null, "ZERO_DENOM", c);
        BigDecimal v = cur.subtract(prev).divide(prev.abs(), MC).multiply(HUNDRED);
        return new MetricValue(corp, f.periodKey(), "REVENUE_YOY", scale(v), MetricValue.OK, c);
    }

    private static Map<String, Object> comp(PeriodFacts f, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>(extra);
        m.put("fsDiv", f.fsDiv());
        m.put("rceptNo", nz(f.rceptNo()));
        m.put("reprtCode", nz(f.reprtCode()));
        return m;
    }

    static BigDecimal scale(BigDecimal v) { return v.setScale(4, RoundingMode.HALF_UP); }

    private static Object nz(Object o) { return o == null ? "-" : o; }

    private static BigDecimal zero(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }
}
