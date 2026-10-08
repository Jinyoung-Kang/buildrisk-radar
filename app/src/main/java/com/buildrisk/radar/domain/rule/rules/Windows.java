package com.buildrisk.radar.domain.rule.rules;

import com.buildrisk.radar.domain.account.PeriodKeys;
import com.buildrisk.radar.domain.metric.RegionMetricCalculator;
import com.buildrisk.radar.domain.rule.RuleModels.Evaluation;
import com.buildrisk.radar.domain.rule.RuleModels.Finding;
import com.buildrisk.radar.domain.rule.RuleModels.MetricPoint;
import com.buildrisk.radar.domain.rule.RuleModels.TargetType;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.function.Predicate;

/** 평가 창·연속 판정 도우미 (분기 · 월) */
final class Windows {
    private Windows() {}

    static List<String> lastN(NavigableMap<String, ?> series, int n) {
        List<String> keys = new ArrayList<>(series.keySet());
        return keys.subList(Math.max(0, keys.size() - n), keys.size());
    }

    static String shift(TargetType t, String period, int by) {
        return t == TargetType.COMPANY ? PeriodKeys.shift(period, by) : RegionMetricCalculator.shift(period, by);
    }

    /**
     * 조건 판정의 세 값. 값이 없거나 상태가 OK 가 아닌 점(MISSING · ZERO_DENOM · NEG_EQUITY …)은 '모름' —
     * '참이 아님'으로 보면 판단할 수 없는 분기에 열린 경보가 '해소'로 닫힘 (실데이터 00122694, VERIFICATION 118)
     */
    enum Verdict {
        TRUE, FALSE, UNKNOWN;

        static Verdict of(MetricPoint p, Predicate<MetricPoint> cond) {
            return p == null || !p.ok() ? UNKNOWN : cond.test(p) ? TRUE : FALSE;
        }

        static Verdict of(boolean b) { return b ? TRUE : FALSE; }

        /** 하나라도 거짓이면 거짓, 모름이 섞이면 모름 */
        Verdict and(Verdict o) { return this == FALSE || o == FALSE ? FALSE : this == UNKNOWN || o == UNKNOWN ? UNKNOWN : TRUE; }

        /** 하나라도 참이면 참, 모름이 섞이면 모름 */
        Verdict or(Verdict o) { return this == TRUE || o == TRUE ? TRUE : this == UNKNOWN || o == UNKNOWN ? UNKNOWN : FALSE; }
    }

    /** period 에서 거꾸로 k 개 연속 기간 모두 조건 — 알려진 점 하나가 깨면 거짓, 깬 점 없이 모르는 점이 있으면 모름 */
    static Verdict run(TargetType t, NavigableMap<String, MetricPoint> s, String period, int k, Predicate<MetricPoint> cond) {
        Verdict v = Verdict.TRUE;
        for (int i = k - 1; i >= 0; i--) v = v.and(Verdict.of(s.get(shift(t, period, -i)), cond));
        return v;
    }

    /** 판단한 결과 — latestAsOf 는 판단할 수 있었던 마지막 기간, 하나도 없으면 결정하지 않음(열린 경보 유지) */
    static Evaluation result(List<Finding> findings, List<String> periods, String lastDecided) {
        return new Evaluation(findings, periods, lastDecided, lastDecided != null);
    }

    /** period 에서 거꾸로 k 개 연속 기간이 모두 조건을 만족하면 그 점들(오래된 것부터), 아니면 null */
    static List<MetricPoint> consecutive(TargetType t, NavigableMap<String, MetricPoint> s, String period, int k,
                                         Predicate<MetricPoint> cond) {
        List<MetricPoint> pts = new ArrayList<>();
        for (int i = k - 1; i >= 0; i--) {
            MetricPoint p = s.get(shift(t, period, -i));
            if (p == null || !p.ok() || !cond.test(p)) return null;
            pts.add(p);
        }
        return pts;
    }
}
