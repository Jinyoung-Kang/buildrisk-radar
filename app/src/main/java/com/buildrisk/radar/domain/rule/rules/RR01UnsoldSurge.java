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

/** R-R01 — UNSOLD_3M_CHG > unsold3mChgPct 이고 UNSOLD_PER_1K_HH > unsoldPer1kHh (미분양 minUnsoldUnits 호 이상) */
public class RR01UnsoldSurge implements RuleEvaluator {
    @Override public String code() { return "R-R01"; }

    @Override public TargetType targetType() { return TargetType.REGION; }

    @Override public void validate(Params p) {
        p.positive("unsold3mChgPct");
        p.positive("unsoldPer1kHh");
        p.count("minUnsoldUnits", 0, 100_000);
    }

    @Override public String condition(Params p) {
        return "미분양 3개월 증감률 > " + Evidence.fmt(p.num("unsold3mChgPct")) + "% 이고 천 가구당 미분양 > "
                + Evidence.fmt(p.num("unsoldPer1kHh")) + "호 (미분양 " + p.count("minUnsoldUnits", 0, 100_000) + "호 이상)";
    }

    @Override
    public Evaluation evaluate(RuleDefinition rule, String region, RuleData data) {
        Params p = new Params(rule.params());
        BigDecimal chgTh = p.num("unsold3mChgPct");
        BigDecimal perTh = p.num("unsoldPer1kHh");
        BigDecimal minUnits = BigDecimal.valueOf(p.count("minUnsoldUnits", 0, 100_000));
        NavigableMap<String, MetricPoint> units = data.regionMetric(region, "UNSOLD_UNITS");
        NavigableMap<String, MetricPoint> chg = data.regionMetric(region, "UNSOLD_3M_CHG");
        NavigableMap<String, MetricPoint> per = data.regionMetric(region, "UNSOLD_PER_1K_HH");
        List<String> periods = Windows.lastN(units, data.windowSize(TargetType.REGION));
        List<Finding> out = new ArrayList<>();
        for (String ym : periods) {
            MetricPoint u = units.get(ym), c = chg.get(ym), h = per.get(ym);
            if (u == null || c == null || h == null || !u.ok() || !c.ok() || !h.ok()) continue;
            if (u.value().compareTo(minUnits) < 0 || c.value().compareTo(chgTh) <= 0 || h.value().compareTo(perTh) <= 0) {
                continue;
            }
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("period", ym);
            o.put("unsoldUnits", u.value());
            o.put("unsold3mAgo", c.components().get("unsold3mAgo"));
            o.put("unsold3mChgPct", Evidence.round(c.value()));
            o.put("unsoldPer1kHh", Evidence.round(h.value()));
            o.put("households", h.components().get("households"));
            o.put("householdsYear", h.components().get("householdsYear"));
            String msg = ym + " 미분양 " + Evidence.fmt(u.value()) + "호로 " + vs3m(c) + " 늘었고, 천 가구당 " + Evidence.fmt(h.value()) + "호입니다.";
            Map<String, Object> ev = new Evidence(rule, condition(p)).observe(o)
                    .source(Map.of("type", "KOSIS", "table", "116/DT_MLTM_2082", "period", ym))
                    .source(Map.of("type", "SGIS", "table", "총조사 주요지표 tot_family",
                            "period", String.valueOf(h.components().get("householdsYear"))))
                    .build(msg);
            out.add(new Finding(ym, "미분양 3개월 +" + Evidence.fmt(c.value()) + "% · 천 가구당 " + Evidence.fmt(h.value()) + "호",
                    msg, ev));
        }
        return new Evaluation(out, periods, periods.isEmpty() ? null : periods.get(periods.size() - 1));
    }

    /** "3개월 전(4호)보다 838호(20,950%)" — 기저가 작으면 증감률만으로는 과장돼 보여 늘어난 호수를 함께 (실측: 4호 → 842호) */
    static String vs3m(MetricPoint chg) {
        Object now = chg.components().get("unsold"), ago = chg.components().get("unsold3mAgo");
        if (now == null || ago == null) return "3개월 전보다 " + Evidence.fmt(chg.value()) + "%";
        BigDecimal a = new BigDecimal(ago.toString());
        return "3개월 전(" + Evidence.fmt(a) + "호)보다 " + Evidence.fmt(new BigDecimal(now.toString()).subtract(a).abs()) + "호("
                + Evidence.fmt(chg.value()) + "%)";
    }
}
