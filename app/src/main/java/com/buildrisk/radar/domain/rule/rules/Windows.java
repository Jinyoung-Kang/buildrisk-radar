package com.buildrisk.radar.domain.rule.rules;

import com.buildrisk.radar.domain.account.PeriodKeys;
import com.buildrisk.radar.domain.metric.RegionMetricCalculator;
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
