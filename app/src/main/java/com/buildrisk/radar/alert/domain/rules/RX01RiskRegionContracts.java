package com.buildrisk.radar.alert.domain.rules;

import com.buildrisk.radar.alert.domain.Evidence;
import com.buildrisk.radar.alert.domain.Params;
import com.buildrisk.radar.alert.domain.RuleData;
import com.buildrisk.radar.alert.domain.RuleEvaluator;
import com.buildrisk.radar.alert.domain.RuleModels.Evaluation;
import com.buildrisk.radar.alert.domain.RuleModels.Finding;
import com.buildrisk.radar.alert.domain.RuleModels.RuleDefinition;
import com.buildrisk.radar.alert.domain.RuleModels.TargetType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R-X01 위험 지역 수주 집중 (기업 × 지역 교차) — 최근 windowDays 일 수주(현재 계약, 국내 시군구 매핑분) 금액 중
 * '천 가구당 미분양 ≥ unsoldPer1kHh' 지역 비중 ≥ sharePct % 이고 그런 계약이 minContracts 건 이상.
 * 재무제표에는 아직 안 보이는 '앞으로의 매출이 어느 시장에 걸려 있나'를 봅니다 (ADR-016). as_of = 평가 분기.
 */
public class RX01RiskRegionContracts implements RuleEvaluator {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override public String code() { return "R-X01"; }

    @Override public TargetType targetType() { return TargetType.COMPANY; }

    @Override public void validate(Params p) {
        p.count("windowDays", 30, 1095);
        p.positive("unsoldPer1kHh");
        BigDecimal share = p.positive("sharePct");
        if (share.compareTo(HUNDRED) > 0) throw new Params.RuleParamException("sharePct 는 100 이하");
        p.count("minContracts", 1, 100);
    }

    @Override public String condition(Params p) {
        return "최근 " + p.count("windowDays", 30, 1095) + "일 수주 금액 중 천 가구당 미분양 ≥ " + Evidence.fmt(p.num("unsoldPer1kHh"))
                + "호 지역 비중 ≥ " + Evidence.fmt(p.num("sharePct")) + "% (해당 계약 " + p.count("minContracts", 1, 100) + "건 이상)";
    }

    @Override
    public Evaluation evaluate(RuleDefinition rule, String corp, RuleData data) {
        Params p = new Params(rule.params());
        LocalDate today = data.today();
        String asOf = today.getYear() + "Q" + ((today.getMonthValue() - 1) / 3 + 1);
        var share = RiskRegionShare.of(data.contracts(corp, today.minusDays(p.count("windowDays", 30, 1095))),
                r -> RiskRegionShare.latestOk(data.regionMetric(r, "UNSOLD_PER_1K_HH")), p.num("unsoldPer1kHh"));
        if (share.isEmpty()) return new Evaluation(List.of(), List.of(asOf), asOf);
        BigDecimal th = p.num("unsoldPer1kHh");
        RiskRegionShare s = share.get();
        if (s.risky().size() < p.count("minContracts", 1, 100) || s.sharePct().compareTo(p.num("sharePct")) < 0) {
            return new Evaluation(List.of(), List.of(asOf), asOf);
        }
        List<Map<String, Object>> riskyRows = new ArrayList<>();
        for (var x : s.risky()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rceptNo", x.contract().rceptNo());
            row.put("contract", x.contract().name());
            row.put("region", x.contract().regionName());
            row.put("amount", x.contract().amount());
            row.put("unsoldPer1kHh", Evidence.round(x.unsold().value()));
            row.put("unsoldPeriod", x.unsold().period());
            riskyRows.add(row);
        }
        BigDecimal total = s.totalAmount(), risky = s.riskAmount(), share100 = s.sharePct();
        riskyRows.sort(Comparator.comparing((Map<String, Object> r) -> (BigDecimal) r.get("amount")).reversed());
        Evidence ev = new Evidence(rule, condition(p));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("asOf", asOf);
        summary.put("contracts", s.contracts());
        summary.put("totalAmount", total);
        summary.put("riskRegionAmount", risky);
        summary.put("riskRegionSharePct", Evidence.round(share100));
        ev.observe(summary);
        riskyRows.stream().limit(5).forEach(ev::observe);
        ev.source(Map.of("type", "DART", "table", "단일판매ㆍ공급계약체결 원문 (구조화)"))
          .source(Map.of("type", "KOSIS·SGIS", "table", "천 가구당 미분양 (지역 지표)"));
        String msg = "최근 수주 " + s.contracts() + "건 " + Evidence.won(total) + " 중 " + Evidence.fmt(share100)
                + "%(" + riskyRows.size() + "건, " + Evidence.won(risky) + ")가 천 가구당 미분양 "
                + Evidence.fmt(th) + "호 이상 지역입니다. 가장 큰 계약: " + riskyRows.get(0).get("contract") + " ("
                + riskyRows.get(0).get("region") + ").";
        return new Evaluation(List.of(new Finding(asOf, "위험 지역 수주 " + Evidence.fmt(share100) + "%", msg, ev.build(msg))),
                List.of(asOf), asOf);
    }
}
