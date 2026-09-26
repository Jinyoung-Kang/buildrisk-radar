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
import java.util.stream.Collectors;

/** R-C02 — INTEREST_COVERAGE < threshold 가 consecutive 분기 연속 */
public class RC02InterestCoverage implements RuleEvaluator {
    @Override public String code() { return "R-C02"; }

    @Override public TargetType targetType() { return TargetType.COMPANY; }

    @Override public void validate(Params p) {
        p.positive("threshold");
        p.count("consecutive", 1, 8);
    }

    @Override public String condition(Params p) {
        return "분기 이자보상배율 < " + Evidence.fmt(p.num("threshold")) + "배가 " + p.count("consecutive", 1, 8) + "개 분기 연속";
    }

    @Override
    public Evaluation evaluate(RuleDefinition rule, String corp, RuleData data) {
        Params p = new Params(rule.params());
        BigDecimal th = p.num("threshold");
        int k = p.count("consecutive", 1, 8);
        NavigableMap<String, MetricPoint> ic = data.companyMetric(corp, "INTEREST_COVERAGE");
        NavigableMap<String, MetricPoint> base = data.companyMetric(corp, "DEBT_RATIO");
        List<String> periods = Windows.lastN(base.isEmpty() ? ic : base, data.windowSize(TargetType.COMPANY));
        List<Finding> out = new ArrayList<>();
        for (String pk : periods) {
            List<MetricPoint> run = Windows.consecutive(TargetType.COMPANY, ic, pk, k, m -> m.value().compareTo(th) < 0);
            if (run == null) continue;
            Evidence ev = new Evidence(rule, condition(p));
            for (MetricPoint m : run) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("periodKey", m.period());
                o.put("value", Evidence.round(m.value()));
                o.put("operatingIncome", ((Map<?, ?>) m.components().getOrDefault("numerator", Map.of())).get("amount"));
                Map<?, ?> den = (Map<?, ?>) m.components().getOrDefault("denominator", Map.of());
                o.put("interestExpense", den.get("amount"));
                o.put("interestExpenseSource", den.get("source"));
                ev.observe(o).sourceOf(m, "DART");
            }
            // 영업손실이면 배율(음수)의 크기는 뜻이 없음 — 분기 이자비용이 5원이면 -60억 배가 됨(실측). 숫자는 observations 에 그대로
            String series = run.stream().map(m -> m.period() + " " + (m.value().signum() < 0 ? "영업손실" : Evidence.fmt(m.value()) + "배"))
                    .collect(Collectors.joining(", "));
            String msg = series + "로 이자보상배율이 " + Evidence.fmt(th) + "배 미만인 분기가 " + k + "개 연속입니다.";
            out.add(new Finding(pk, "이자보상배율 " + Evidence.fmt(th) + " 미만 " + k + "분기 연속", msg, ev.build(msg)));
        }
        return new Evaluation(out, periods, periods.isEmpty() ? null : periods.get(periods.size() - 1));
    }
}
