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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/** R-C01 — DEBT_RATIO > debtRatioThreshold 이고 DEBT_RATIO_YOY > yoyIncreasePp */
public class RC01DebtRatioSurge implements RuleEvaluator {
    @Override public String code() { return "R-C01"; }

    @Override public TargetType targetType() { return TargetType.COMPANY; }

    @Override public void validate(Params p) {
        p.positive("debtRatioThreshold");
        p.positive("yoyIncreasePp");
    }

    @Override public String condition(Params p) {
        return "부채비율 > " + Evidence.fmt(p.num("debtRatioThreshold")) + "% 이고 전년 동기 대비 +"
                + Evidence.fmt(p.num("yoyIncreasePp")) + "%p 초과";
    }

    @Override
    public Evaluation evaluate(RuleDefinition rule, String corp, RuleData data) {
        Params p = new Params(rule.params());
        BigDecimal th = p.num("debtRatioThreshold");
        BigDecimal yoyTh = p.num("yoyIncreasePp");
        NavigableMap<String, MetricPoint> dr = data.companyMetric(corp, "DEBT_RATIO");
        NavigableMap<String, MetricPoint> yoy = data.companyMetric(corp, "DEBT_RATIO_YOY");
        List<String> periods = Windows.lastN(dr, data.windowSize(TargetType.COMPANY));
        List<Finding> out = new ArrayList<>();
        for (String pk : periods) {
            MetricPoint d = dr.get(pk);
            MetricPoint y = yoy.get(pk);
            if (d == null || y == null || !d.ok() || !y.ok()) continue;
            if (d.value().compareTo(th) <= 0 || y.value().compareTo(yoyTh) <= 0) continue;
            String msg = pk + " 부채비율 " + Evidence.fmt(d.value()) + "%로 임계 " + Evidence.fmt(th)
                    + "%를 넘었고, 전년 같은 분기보다 " + Evidence.fmt(y.value()) + "%p 올랐습니다.";
            Map<String, Object> obs = new LinkedHashMap<>();
            obs.put("periodKey", pk);
            obs.put("debtRatio", Evidence.round(d.value()));
            obs.put("debtRatioYoyPp", Evidence.round(y.value()));
            obs.put("totalLiabilities", ((Map<?, ?>) d.components().getOrDefault("numerator", Map.of())).get("amount"));
            obs.put("totalEquity", ((Map<?, ?>) d.components().getOrDefault("denominator", Map.of())).get("amount"));
            Evidence ev = new Evidence(rule, condition(p)).observe(obs).sourceOf(d, "DART");
            MetricPoint ago = dr.get(com.buildrisk.radar.domain.account.PeriodKeys.yearAgo(pk));
            if (ago != null) ev.sourceOf(ago, "DART");
            out.add(new Finding(pk, "부채비율 " + Evidence.fmt(d.value()) + "% · 전년 대비 +" + Evidence.fmt(y.value()) + "%p",
                    msg, ev.build(msg)));
        }
        return new Evaluation(out, periods, periods.isEmpty() ? null : periods.get(periods.size() - 1));
    }
}
