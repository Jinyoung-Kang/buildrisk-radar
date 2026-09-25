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

/**
 * R-R03 거래 절벽 + 미분양 증가 — TRADE_YOY ≤ −tradeDropPct 이고 UNSOLD_3M_CHG ≥ unsold3mChgPct (같은 달),
 * 전년 같은 달 거래가 minTradesYearAgo 건 이상일 때만 (거래가 적은 군 지역의 우연한 변동 제외).
 * 가격지수(R-R02)보다 먼저 움직이는 '거래량'으로 분양 시장 냉각을 잡습니다 (ADR-015).
 */
public class RR03TradeCliffUnsold implements RuleEvaluator {
    @Override public String code() { return "R-R03"; }

    @Override public TargetType targetType() { return TargetType.REGION; }

    @Override public void validate(Params p) {
        BigDecimal drop = p.positive("tradeDropPct");
        if (drop.compareTo(BigDecimal.valueOf(100)) >= 0) throw new Params.RuleParamException("tradeDropPct 는 100 미만이어야 합니다.");
        p.positive("unsold3mChgPct");
        p.count("minTradesYearAgo", 1, 100_000);
    }

    @Override public String condition(Params p) {
        return "아파트 거래량 전년 동월 대비 ≤ −" + Evidence.fmt(p.num("tradeDropPct")) + "% 이고 미분양 3개월 증감률 ≥ "
                + Evidence.fmt(p.num("unsold3mChgPct")) + "% (전년 같은 달 거래 " + p.count("minTradesYearAgo", 1, 100_000) + "건 이상)";
    }

    @Override
    public Evaluation evaluate(RuleDefinition rule, String region, RuleData data) {
        Params p = new Params(rule.params());
        BigDecimal drop = p.num("tradeDropPct").negate();
        BigDecimal unsoldTh = p.num("unsold3mChgPct");
        BigDecimal minPrev = BigDecimal.valueOf(p.count("minTradesYearAgo", 1, 100_000));
        NavigableMap<String, MetricPoint> yoy = data.regionMetric(region, "TRADE_YOY");
        NavigableMap<String, MetricPoint> unsold = data.regionMetric(region, "UNSOLD_3M_CHG");
        List<String> periods = Windows.lastN(yoy, data.windowSize(TargetType.REGION));
        List<Finding> out = new ArrayList<>();
        for (String ym : periods) {
            MetricPoint t = yoy.get(ym), u = unsold.get(ym);
            if (t == null || u == null || !t.ok() || !u.ok()) continue;
            Object prevObj = t.components().get("trades12mAgo");
            BigDecimal prev = prevObj instanceof Number n ? new BigDecimal(n.toString())
                    : prevObj == null ? null : parse(prevObj.toString());
            if (prev == null || prev.compareTo(minPrev) < 0) continue;
            if (t.value().compareTo(drop) > 0 || u.value().compareTo(unsoldTh) < 0) continue;
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("period", ym);
            o.put("trades", t.components().get("trades"));
            o.put("trades12mAgo", prev);
            o.put("tradeYoyPct", Evidence.round(t.value()));
            o.put("unsold3mChgPct", Evidence.round(u.value()));
            String msg = ym + " 아파트 매매 " + t.components().get("trades") + "건으로 전년 같은 달(" + Evidence.fmt(prev)
                    + "건)보다 " + Evidence.fmt(t.value().negate()) + "% 줄었고, 미분양은 3개월 전보다 "
                    + Evidence.fmt(u.value()) + "% 늘었습니다.";
            Map<String, Object> ev = new Evidence(rule, condition(p)).observe(o)
                    .source(Map.of("type", "RTMS", "table", "RTMSDataSvcAptTrade", "period", ym))
                    .source(Map.of("type", "KOSIS", "table", "116/DT_MLTM_2082", "period", ym))
                    .build(msg);
            out.add(new Finding(ym, "거래 " + Evidence.fmt(t.value()) + "% · 미분양 +" + Evidence.fmt(u.value()) + "%", msg, ev));
        }
        return new Evaluation(out, periods, periods.isEmpty() ? null : periods.get(periods.size() - 1));
    }

    private static BigDecimal parse(String s) {
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
