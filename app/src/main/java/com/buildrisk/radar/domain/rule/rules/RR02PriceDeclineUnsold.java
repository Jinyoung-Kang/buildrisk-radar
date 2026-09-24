package com.buildrisk.radar.domain.rule.rules;

import com.buildrisk.radar.domain.rule.Evidence;
import com.buildrisk.radar.domain.rule.Params;
import com.buildrisk.radar.domain.rule.RuleData;
import com.buildrisk.radar.domain.rule.RuleEvaluator;
import com.buildrisk.radar.domain.rule.RuleModels.Evaluation;
import com.buildrisk.radar.domain.rule.RuleModels.Finding;
import com.buildrisk.radar.domain.rule.RuleModels.MetricPoint;
import com.buildrisk.radar.domain.rule.RuleModels.RuleDefinition;
import com.buildrisk.radar.domain.rule.RuleModels.TargetType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.stream.Collectors;

/** R-R02 — PRICE_IDX_3M_CHG < 0 이 consecutive 개월 연속 이고 UNSOLD_3M_CHG > 0 */
public class RR02PriceDeclineUnsold implements RuleEvaluator {
    @Override public String code() { return "R-R02"; }

    @Override public TargetType targetType() { return TargetType.REGION; }

    @Override public void validate(Params p) { p.count("consecutive", 1, 12); }

    @Override public String condition(Params p) {
        return "아파트 매매가격지수 3개월 변화 < 0 이 " + p.count("consecutive", 1, 12) + "개월 연속 이고 미분양 3개월 증감률 > 0";
    }

    @Override
    public Evaluation evaluate(RuleDefinition rule, String region, RuleData data) {
        Params p = new Params(rule.params());
        int k = p.count("consecutive", 1, 12);
        NavigableMap<String, MetricPoint> price = data.regionMetric(region, "PRICE_IDX_3M_CHG");
        NavigableMap<String, MetricPoint> unsold = data.regionMetric(region, "UNSOLD_3M_CHG");
        NavigableMap<String, MetricPoint> base = data.regionMetric(region, "UNSOLD_UNITS");
        List<String> periods = Windows.lastN(base.isEmpty() ? price : base, data.windowSize(TargetType.REGION));
        List<Finding> out = new ArrayList<>();
        for (String ym : periods) {
            MetricPoint u = unsold.get(ym);
            if (u == null || !u.ok() || u.value().signum() <= 0) continue;
            List<MetricPoint> run = Windows.consecutive(TargetType.REGION, price, ym, k, m -> m.value().signum() < 0);
            if (run == null) continue;
            Evidence ev = new Evidence(rule, condition(p));
            for (MetricPoint m : run) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("period", m.period());
                o.put("priceIdx3mChg", Evidence.round(m.value()));
                o.put("saleIdx", m.components().get("saleIdx"));
                ev.observe(o);
            }
            ev.observe(Map.of("period", ym, "unsold3mChgPct", Evidence.round(u.value())));
            ev.source(Map.of("type", "RONE", "table", "A_2024_00045", "period", ym))
              .source(Map.of("type", "KOSIS", "table", "116/DT_MLTM_2082", "period", ym));
            String series = run.stream().map(m -> m.period() + " " + Evidence.fmt(m.value()) + "pt")
                    .collect(Collectors.joining(", "));
            String msg = "매매가격지수 3개월 변화가 " + series + "로 " + k + "개월 연속 하락했고, 미분양은 3개월 전보다 "
                    + Evidence.fmt(u.value()) + "% 늘었습니다.";
            out.add(new Finding(ym, "가격 " + k + "개월 연속 하락 + 미분양 증가", msg, ev.build(msg)));
        }
        return new Evaluation(out, periods, periods.isEmpty() ? null : periods.get(periods.size() - 1));
    }
}
