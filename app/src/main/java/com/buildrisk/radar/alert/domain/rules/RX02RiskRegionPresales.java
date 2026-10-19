package com.buildrisk.radar.alert.domain.rules;

import com.buildrisk.radar.alert.domain.Evidence;
import com.buildrisk.radar.alert.domain.Params;
import com.buildrisk.radar.alert.domain.RuleData;
import com.buildrisk.radar.alert.domain.RuleEvaluator;
import com.buildrisk.radar.alert.domain.RuleModels.Evaluation;
import com.buildrisk.radar.alert.domain.RuleModels.Finding;
import com.buildrisk.radar.alert.domain.RuleModels.RuleDefinition;
import com.buildrisk.radar.alert.domain.RuleModels.TargetType;
import com.buildrisk.radar.presale.domain.PresaleRegionShare;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R-X02 위험 지역 분양 집중 (기업 × 지역 교차, ADR-032) — 최근 windowDays 일 청약홈 APT 분양 공고 중 이 기업이 시공사(MATCHED)이고
 * 시군구로 풀린 공고의 세대 가운데, '천 가구당 미분양 ≥ unsoldPer1kHh' 지역 세대 비중 ≥ sharePct % 이고 그런 공고가 minNotices 건 이상.
 * 미분양 지표가 없는 지역의 공고는 분모에서 뺌. 컨소시엄은 지분을 몰라 세대를 나누지 않음(과대 가능 — 근거에 시공사 목록).
 * 수주 공시(R-X01)보다 앞선 신호: 분양은 착공 · 입주 전, 미분양이 쌓이기 전 단계. as_of = 평가 분기.
 */
public class RX02RiskRegionPresales implements RuleEvaluator {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override public String code() { return "R-X02"; }

    @Override public TargetType targetType() { return TargetType.COMPANY; }

    @Override public void validate(Params p) {
        p.count("windowDays", 30, 1095);
        p.positive("unsoldPer1kHh");
        BigDecimal share = p.positive("sharePct");
        if (share.compareTo(HUNDRED) > 0) throw new Params.RuleParamException("sharePct 는 100 이하");
        p.count("minNotices", 1, 100);
    }

    @Override public String condition(Params p) {
        return "최근 " + p.count("windowDays", 30, 1095) + "일 분양 공고 세대 중 천 가구당 미분양 ≥ " + Evidence.fmt(p.num("unsoldPer1kHh"))
                + "호 지역 비중 ≥ " + Evidence.fmt(p.num("sharePct")) + "% (해당 공고 " + p.count("minNotices", 1, 100) + "건 이상)";
    }

    @Override
    public Evaluation evaluate(RuleDefinition rule, String corp, RuleData data) {
        Params p = new Params(rule.params());
        LocalDate today = data.today();
        String asOf = today.getYear() + "Q" + ((today.getMonthValue() - 1) / 3 + 1);
        BigDecimal th = p.num("unsoldPer1kHh");
        var share = PresaleRegionShare.of(data.presales(corp, today.minusDays(p.count("windowDays", 30, 1095))),
                r -> PresaleRegionShare.latestOk(data.regionMetric(r, "UNSOLD_PER_1K_HH")), th);
        if (share.isEmpty()) return new Evaluation(List.of(), List.of(asOf), asOf);
        PresaleRegionShare s = share.get();
        if (s.risky().size() < p.count("minNotices", 1, 100) || s.sharePct().compareTo(p.num("sharePct")) < 0) {
            return new Evaluation(List.of(), List.of(asOf), asOf);
        }
        List<Map<String, Object>> riskyRows = new ArrayList<>();
        for (var x : s.risky()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("houseManageNo", x.fact().houseManageNo());
            row.put("pblancNo", x.fact().pblancNo());
            row.put("houseNm", x.fact().houseNm());
            row.put("noticeDate", x.fact().noticeDate().toString());
            row.put("regionCd", x.fact().regionCd());
            row.put("regionName", x.fact().regionName());
            row.put("units", x.fact().units());
            row.put("unsoldPer1kHh", Evidence.round(x.unsold().value()));
            row.put("unsoldPeriod", x.unsold().period());
            row.put("partners", x.fact().partners());
            riskyRows.add(row);
        }
        riskyRows.sort(Comparator.comparing((Map<String, Object> r) -> (Integer) r.get("units")).reversed());
        Evidence ev = new Evidence(rule, condition(p));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("asOf", asOf);
        summary.put("notices", s.notices());
        summary.put("totalUnits", s.totalUnits());
        summary.put("riskRegionUnits", s.riskUnits());
        summary.put("riskRegionSharePct", Evidence.round(s.sharePct()));
        summary.put("excludedNoUnsoldMetric", s.excluded());
        ev.observe(summary);
        riskyRows.stream().limit(5).forEach(ev::observe);
        ev.source(Map.of("type", "청약홈", "table", "APT 분양정보 상세 (getAPTLttotPblancDetail) · 시공사 대조"))
          .source(Map.of("type", "KOSIS·SGIS", "table", "천 가구당 미분양 (지역 지표)"));
        Map<String, Object> top = riskyRows.get(0);
        String msg = "최근 분양 공고 " + s.notices() + "건 " + Evidence.fmt(BigDecimal.valueOf(s.totalUnits())) + "세대 중 "
                + Evidence.fmt(s.sharePct()) + "%(" + riskyRows.size() + "건, " + Evidence.fmt(BigDecimal.valueOf(s.riskUnits()))
                + "세대)가 천 가구당 미분양 " + Evidence.fmt(th) + "호 이상 지역입니다. 가장 큰 공고: " + top.get("houseNm") + " ("
                + top.get("regionName") + ", " + top.get("units") + "세대)."
                + (((List<?>) top.get("partners")).isEmpty() ? "" : " 컨소시엄 — 지분 미공개라 세대를 나누지 않음.");
        return new Evaluation(List.of(new Finding(asOf, "위험 지역 분양 " + Evidence.fmt(s.sharePct()) + "%", msg, ev.build(msg))),
                List.of(asOf), asOf);
    }
}
