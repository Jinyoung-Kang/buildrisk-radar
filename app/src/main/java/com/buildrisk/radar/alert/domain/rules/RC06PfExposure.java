package com.buildrisk.radar.alert.domain.rules;

import com.buildrisk.radar.alert.domain.Evidence;
import com.buildrisk.radar.alert.domain.Params;
import com.buildrisk.radar.alert.domain.RuleData;
import com.buildrisk.radar.alert.domain.RuleEvaluator;
import com.buildrisk.radar.alert.domain.RuleModels.Evaluation;
import com.buildrisk.radar.alert.domain.RuleModels.Finding;
import com.buildrisk.radar.alert.domain.RuleModels.RuleDefinition;
import com.buildrisk.radar.alert.domain.RuleModels.TargetType;
import com.buildrisk.radar.metric.domain.MetricPoint;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * R-C06 — PF 우발채무 과다 (ADR-024): 정기보고서 PF 종합요약표의 보증금액 / 자기자본 ≥ guaranteeToEquityPct
 * 또는 12개월 안에 만기가 돌아오는 PF 대출잔액 / 자기자본 ≥ due12mToEquityPct.
 * R-C05(채무보증 결정 공시, 수시)와 달리 분기마다 회사 전체 PF 를 표준 서식으로 봄.
 */
public class RC06PfExposure implements RuleEvaluator {
    @Override public String code() { return "R-C06"; }

    @Override public List<String> evidenceMetrics() { return List.of("PF_GUARANTEE_TO_EQUITY", "PF_DUE_12M_TO_EQUITY", "PF_BRIDGE_SHARE"); }

    @Override public TargetType targetType() { return TargetType.COMPANY; }

    @Override public void validate(Params p) {
        p.positive("guaranteeToEquityPct");
        p.positive("due12mToEquityPct");
    }

    @Override public String condition(Params p) {
        return "PF 보증 / 자기자본 ≥ " + Evidence.fmt(p.num("guaranteeToEquityPct")) + "% 또는 12개월 내 만기 PF / 자기자본 ≥ "
                + Evidence.fmt(p.num("due12mToEquityPct")) + "% (정기보고서 부동산 PF 종합요약표, 단독 + 컨소시엄 회사분)";
    }

    @Override
    public Evaluation evaluate(RuleDefinition rule, String corp, RuleData data) {
        Params p = new Params(rule.params());
        BigDecimal gTh = p.num("guaranteeToEquityPct"), dTh = p.num("due12mToEquityPct");
        NavigableMap<String, MetricPoint> g = data.companyMetric(corp, "PF_GUARANTEE_TO_EQUITY");
        NavigableMap<String, MetricPoint> d = data.companyMetric(corp, "PF_DUE_12M_TO_EQUITY");
        NavigableMap<String, MetricPoint> b = data.companyMetric(corp, "PF_BRIDGE_SHARE");
        List<String> periods = Windows.lastN(g, data.windowSize(TargetType.COMPANY));
        List<Finding> out = new ArrayList<>();
        String decided = null;
        for (String pk : periods) {
            MetricPoint gp = g.get(pk), dp = d.get(pk), bp = b.get(pk);
            // 보증 비율을 알아야 판단 — 12개월 만기 지표가 아예 없으면(옛 서식) 거짓, 있는데 OK 가 아니면 모름
            Windows.Verdict dv = dp == null ? Windows.Verdict.FALSE : Windows.Verdict.of(dp, m -> m.value().compareTo(dTh) >= 0);
            Windows.Verdict v = gp == null || !gp.ok() ? Windows.Verdict.UNKNOWN
                    : Windows.Verdict.of(gp, m -> m.value().compareTo(gTh) >= 0).or(dv);
            if (v != Windows.Verdict.UNKNOWN) decided = pk;
            if (gp == null || !gp.ok()) continue;
            boolean overG = gp.value().compareTo(gTh) >= 0;
            boolean overD = dp != null && dp.ok() && dp.value().compareTo(dTh) >= 0;
            if (!overG && !overD) continue;
            BigDecimal guarantee = amount(gp, "numerator"), equity = amount(gp, "denominator"), due = dp == null ? null : amount(dp, "numerator");
            StringBuilder msg = new StringBuilder(pk).append(" 부동산 PF 보증 ").append(Evidence.won(guarantee))
                    .append("으로 자기자본(").append(Evidence.won(equity)).append(")의 ").append(Evidence.fmt(gp.value())).append("%");
            if (dp != null && dp.ok()) msg.append(", 12개월 안에 만기가 돌아오는 PF 대출이 ").append(Evidence.won(due))
                    .append("(자기자본의 ").append(Evidence.fmt(dp.value())).append("%)");
            msg.append("입니다.");
            if (bp != null && bp.ok() && bp.value().signum() > 0) msg.append(" 보증 중 브릿지론 비중 ").append(Evidence.fmt(bp.value())).append("%.");
            if (Boolean.FALSE.equals(gp.components().get("reconciled"))) msg.append(" (원문의 사업장별 내역 합이 요약표 합계와 맞지 않음 — 요약표 기준)");
            if (Boolean.FALSE.equals(gp.components().get("statedMatch")) && gp.components().get("statedTotal") != null) {
                msg.append(" (보고서 본문에 적힌 합계 ").append(Evidence.won(new BigDecimal(gp.components().get("statedTotal").toString())))
                        .append("와 다름 — 본문이 컨소시엄 전체분 · 대출잔액 등 다른 기준일 수 있어 요약표 기준)");
            }
            Map<String, Object> obs = new LinkedHashMap<>();
            obs.put("periodKey", pk);
            obs.put("pfGuaranteeToEquityPct", Evidence.round(gp.value()));
            obs.put("pfDue12mToEquityPct", dp == null || !dp.ok() ? null : Evidence.round(dp.value()));
            obs.put("pfBridgeSharePct", bp == null || !bp.ok() ? null : Evidence.round(bp.value()));
            obs.put("pfGuarantee", guarantee);
            obs.put("pfDue12m", due);
            obs.put("totalEquity", equity);
            obs.put("reconciled", gp.components().get("reconciled"));
            obs.put("statedMatch", gp.components().get("statedMatch"));
            Evidence ev = new Evidence(rule, condition(p)).observe(obs).sourceOf(gp, "DART");
            out.add(new Finding(pk, "PF 보증 자기자본의 " + Evidence.fmt(gp.value()) + "%"
                    + (overD ? " · 12개월 내 만기 " + Evidence.fmt(dp.value()) + "%" : ""), msg.toString(), ev.build(msg.toString())));
        }
        return Windows.result(out, periods, decided);
    }

    private static BigDecimal amount(MetricPoint m, String part) {
        Object o = ((Map<?, ?>) m.components().getOrDefault(part, Map.of())).get("amount");
        return o == null ? null : new BigDecimal(o.toString());
    }
}
