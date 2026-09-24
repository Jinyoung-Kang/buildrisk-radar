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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.stream.Collectors;

/** R-C03 — 분기 영업활동현금흐름(OCF_QTR) < 0 이 consecutive 분기 연속 */
public class RC03NegativeOcf implements RuleEvaluator {
    private static final BigDecimal EOK = BigDecimal.valueOf(100_000_000L);

    @Override public String code() { return "R-C03"; }

    @Override public TargetType targetType() { return TargetType.COMPANY; }

    @Override public void validate(Params p) { p.count("consecutive", 1, 8); }

    @Override public String condition(Params p) {
        return "분기 영업활동현금흐름 < 0 이 " + p.count("consecutive", 1, 8) + "개 분기 연속";
    }

    @Override
    public Evaluation evaluate(RuleDefinition rule, String corp, RuleData data) {
        Params p = new Params(rule.params());
        int k = p.count("consecutive", 1, 8);
        NavigableMap<String, MetricPoint> ocf = data.companyMetric(corp, "OCF_QTR");
        NavigableMap<String, MetricPoint> base = data.companyMetric(corp, "DEBT_RATIO");
        List<String> periods = Windows.lastN(base.isEmpty() ? ocf : base, data.windowSize(TargetType.COMPANY));
        List<Finding> out = new ArrayList<>();
        for (String pk : periods) {
            List<MetricPoint> run = Windows.consecutive(TargetType.COMPANY, ocf, pk, k, m -> m.value().signum() < 0);
            if (run == null) continue;
            Evidence ev = new Evidence(rule, condition(p));
            for (MetricPoint m : run) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("periodKey", m.period());
                o.put("operatingCashFlowQtr", m.value());
                ev.observe(o).sourceOf(m, "DART");
            }
            String series = run.stream().map(m -> m.period() + " " + eok(m.value()) + "억 원")
                    .collect(Collectors.joining(", "));
            String msg = "분기 영업활동현금흐름이 " + series + "로 " + k + "개 분기 연속 음수입니다.";
            out.add(new Finding(pk, "영업현금흐름 " + k + "분기 연속 적자", msg, ev.build(msg)));
        }
        return new Evaluation(out, periods, periods.isEmpty() ? null : periods.get(periods.size() - 1));
    }

    private static String eok(BigDecimal won) {
        return won.divide(EOK, 0, RoundingMode.HALF_UP).toPlainString();
    }
}
