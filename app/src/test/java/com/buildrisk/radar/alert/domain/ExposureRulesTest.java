package com.buildrisk.radar.alert.domain;

import com.buildrisk.radar.alert.app.RuleRegistry;
import com.buildrisk.radar.alert.domain.RuleModels.RuleDefinition;
import com.buildrisk.radar.alert.domain.RuleModels.TargetType;
import com.buildrisk.radar.filing.domain.FilingModels.ContractFact;
import com.buildrisk.radar.filing.domain.FilingModels.DisclosureEvent;
import com.buildrisk.radar.filing.domain.FilingModels.GuaranteeFact;
import com.buildrisk.radar.metric.domain.MetricPoint;
import com.buildrisk.radar.presale.domain.PresaleModels.PresaleFact;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-016 규칙 — R-C05 채무보증 잔액 · R-X01 위험 지역 수주 집중 · R-R03 거래 절벽 */
class ExposureRulesTest {
    final RuleRegistry registry = new RuleRegistry();
    static final LocalDate TODAY = LocalDate.of(2026, 9, 25);

    static class Mem implements RuleData {
        final Map<String, NavigableMap<String, MetricPoint>> region = new HashMap<>();
        final List<ContractFact> contracts = new ArrayList<>();
        final List<GuaranteeFact> guarantees = new ArrayList<>();
        final List<PresaleFact> presales = new ArrayList<>();

        Mem metric(String regionCd, String code, String period, String value, Map<String, Object> comp) {
            region.computeIfAbsent(regionCd + ":" + code, k -> new TreeMap<>())
                    .put(period, new MetricPoint(period, new BigDecimal(value), "OK", comp));
            return this;
        }

        @Override public NavigableMap<String, MetricPoint> companyMetric(String c, String m) { return new TreeMap<>(); }
        @Override public NavigableMap<String, MetricPoint> regionMetric(String r, String m) {
            return region.getOrDefault(r + ":" + m, new TreeMap<>());
        }
        @Override public List<DisclosureEvent> disclosures(String c, LocalDate since) { return List.of(); }
        @Override public List<ContractFact> contracts(String c, LocalDate since) {
            return contracts.stream().filter(x -> !x.rceptDt().isBefore(since)).toList();
        }
        @Override public List<GuaranteeFact> guarantees(String c, LocalDate since) {
            return guarantees.stream().filter(x -> !x.rceptDt().isBefore(since)).toList();
        }
        @Override public List<PresaleFact> presales(String c, LocalDate since) {
            return presales.stream().filter(x -> !x.noticeDate().isBefore(since)).toList();
        }
        @Override public int windowSize(TargetType t) { return 12; }
        @Override public LocalDate today() { return TODAY; }
    }

    static RuleDefinition rule(String code, TargetType t, Map<String, Object> params) {
        return new RuleDefinition(code, 1, t, code, "", params, "HIGH", true);
    }

    static GuaranteeFact g(String no, String date, long balance, long equity, Long pf) {
        return new GuaranteeFact(no, LocalDate.parse(date), "채무자", BigDecimal.valueOf(1), BigDecimal.valueOf(equity),
                BigDecimal.valueOf(balance), pf == null ? null : BigDecimal.valueOf(pf));
    }

    @Test
    void R_C05_가장_최근_보증_공시의_잔액_대비_자기자본으로_판단() {
        var d = new Mem();
        var r = rule("R-C05", TargetType.COMPANY, Map.of("balanceToEquityPct", 100, "windowDays", 365));
        d.guarantees.add(g("20260301000001", "2026-03-01", 300, 100, 50L));     // 300% 였지만
        d.guarantees.add(g("20260901000001", "2026-09-01", 80, 100, 20L));      // 최신은 80%
        var ev = registry.get("R-C05").evaluate(r, "c", d);
        assertThat(ev.findings()).isEmpty();
        assertThat(ev.latestAsOf()).isEqualTo("20260901000001");                  // 이전 경보는 SUPERSEDED 로 닫힘

        d.guarantees.add(g("20260920000001", "2026-09-20", 250, 100, null));
        ev = registry.get("R-C05").evaluate(r, "c", d);
        assertThat(ev.findings()).hasSize(1);
        var f = ev.findings().get(0);
        assertThat(f.asOf()).isEqualTo("20260920000001");
        assertThat(f.title()).contains("250");
        @SuppressWarnings("unchecked")
        var obs = (List<Map<String, Object>>) f.evidence().get("observations");
        assertThat(obs.get(0)).containsEntry("pfGuaranteeInWindow", BigDecimal.valueOf(70)).containsEntry("guaranteesInWindow", 3);
        assertThat((List<?>) f.evidence().get("sources")).isNotEmpty();
    }

    @Test
    void R_C05_자기자본이_없으면_판단하지_않는다() {
        var d = new Mem();
        d.guarantees.add(new GuaranteeFact("20260920000002", TODAY.minusDays(5), "x", null, null, BigDecimal.TEN, null));
        var r = rule("R-C05", TargetType.COMPANY, Map.of("balanceToEquityPct", 100, "windowDays", 365));
        assertThat(registry.get("R-C05").evaluate(r, "c", d).findings()).isEmpty();
    }

    static ContractFact c(String no, String date, long amount, String region) {
        return new ContractFact(no, LocalDate.parse(date), "공사 " + no, BigDecimal.valueOf(amount), region,
                region == null ? null : "지역 " + region, region == null ? "OVERSEAS" : "SIGUNGU");
    }

    @Test
    void R_X01_미분양_많은_지역_비중이_기준_이상이면_참() {
        var d = new Mem()
                .metric("11111", "UNSOLD_PER_1K_HH", "202607", "8.0", Map.of())      // 위험 지역
                .metric("22222", "UNSOLD_PER_1K_HH", "202607", "1.0", Map.of());     // 아님
        d.contracts.add(c("20260101000001", "2026-01-10", 600, "11111"));
        d.contracts.add(c("20260201000001", "2026-02-10", 300, "11111"));
        d.contracts.add(c("20260301000001", "2026-03-10", 400, "22222"));
        d.contracts.add(c("20260401000001", "2026-04-10", 9_999, null));             // 해외·미매핑은 분모에서 제외
        var r = rule("R-X01", TargetType.COMPANY, Map.of("windowDays", 365, "unsoldPer1kHh", 5, "sharePct", 50, "minContracts", 2));
        var ev = registry.get("R-X01").evaluate(r, "c", d);
        assertThat(ev.findings()).hasSize(1);
        assertThat(ev.latestAsOf()).isEqualTo("2026Q3");
        var f = ev.findings().get(0);
        assertThat(f.title()).contains("69.23");                                   // 900 / 1300
        assertThat(f.message()).contains("공사 20260101000001");                   // 가장 큰 위험 계약

        var strict = rule("R-X01", TargetType.COMPANY, Map.of("windowDays", 365, "unsoldPer1kHh", 5, "sharePct", 70, "minContracts", 2));
        assertThat(registry.get("R-X01").evaluate(strict, "c", d).findings()).isEmpty();
        var many = rule("R-X01", TargetType.COMPANY, Map.of("windowDays", 365, "unsoldPer1kHh", 5, "sharePct", 50, "minContracts", 3));
        assertThat(registry.get("R-X01").evaluate(many, "c", d).findings()).isEmpty();
    }

    static PresaleFact p(String no, String date, String region, int units, List<String> partners) {
        return new PresaleFact(no, no, "단지" + no, LocalDate.parse(date), region, region == null ? null : "지역" + region,
                region == null ? "SIDO" : "SIGUNGU", units, partners);
    }

    @Test
    void R_X02_미분양_많은_지역_분양_세대_비중이_기준_이상이면_참() {
        var d = new Mem()
                .metric("11111", "UNSOLD_PER_1K_HH", "202607", "8.0", Map.of())      // 위험 지역
                .metric("22222", "UNSOLD_PER_1K_HH", "202607", "1.0", Map.of());     // 아님
        d.presales.add(p("A", "2026-01-10", "11111", 600, List.of("(주)동반건설")));
        d.presales.add(p("B", "2026-02-10", "11111", 300, List.of()));
        d.presales.add(p("C", "2026-03-10", "22222", 400, List.of()));
        d.presales.add(p("D", "2026-04-10", "33333", 9_999, List.of()));             // 미분양 지표 없는 지역 — 분모 제외
        d.presales.add(p("E", "2026-04-11", null, 9_999, List.of()));                // 시도만 — 제외
        d.presales.add(p("F", "2025-01-10", "11111", 9_999, List.of()));             // 1년 밖
        var r = rule("R-X02", TargetType.COMPANY, Map.of("windowDays", 365, "unsoldPer1kHh", 5, "sharePct", 50, "minNotices", 2));
        var ev = registry.get("R-X02").evaluate(r, "c", d);
        assertThat(ev.findings()).hasSize(1);
        assertThat(ev.latestAsOf()).isEqualTo("2026Q3");
        var f = ev.findings().get(0);
        assertThat(f.title()).contains("69.23");                                   // 900 / 1300
        assertThat(f.message()).contains("단지A").contains("컨소시엄");              // 가장 큰 위험 공고 · 지분 미공개
        assertThat((List<?>) f.evidence().get("observations")).first().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("excludedNoUnsoldMetric", 1);

        var strict = rule("R-X02", TargetType.COMPANY, Map.of("windowDays", 365, "unsoldPer1kHh", 5, "sharePct", 70, "minNotices", 2));
        assertThat(registry.get("R-X02").evaluate(strict, "c", d).findings()).isEmpty();
        var many = rule("R-X02", TargetType.COMPANY, Map.of("windowDays", 365, "unsoldPer1kHh", 5, "sharePct", 50, "minNotices", 3));
        var none = registry.get("R-X02").evaluate(many, "c", d);
        assertThat(none.findings()).isEmpty();
        assertThat(none.decided()).isTrue();                                       // 판단은 함 — 열린 경보가 있으면 닫힘 (R-X01 과 같음)
        assertThat(none.evaluatedAsOfs()).containsExactly("2026Q3");
        assertThat(registry.get("R-X02").evaluate(r, "c", new Mem()).findings()).isEmpty();
    }

    @Test
    void R_R03_거래_급감과_미분양_증가가_같은_달이어야_참() {
        var d = new Mem()
                .metric("41110", "TRADE_YOY", "202606", "-55", Map.of("trades", 45, "trades12mAgo", 100))
                .metric("41110", "UNSOLD_3M_CHG", "202606", "25", Map.of())
                .metric("41110", "TRADE_YOY", "202607", "-60", Map.of("trades", 8, "trades12mAgo", 20))   // 표본 작음
                .metric("41110", "UNSOLD_3M_CHG", "202607", "30", Map.of());
        var r = rule("R-R03", TargetType.REGION, Map.of("tradeDropPct", 40, "unsold3mChgPct", 10, "minTradesYearAgo", 30));
        var ev = registry.get("R-R03").evaluate(r, "41110", d);
        assertThat(ev.findings()).extracting(f -> f.asOf()).containsExactly("202606");
        assertThat(ev.findings().get(0).message()).contains("45건").contains("100건");
    }

    @Test
    void 파라미터_검증() {
        assertThatThrownBy(() -> registry.get("R-X01").validate(new Params(
                Map.of("windowDays", 365, "unsoldPer1kHh", 5, "sharePct", 150, "minContracts", 2)))).hasMessageContaining("sharePct");
        assertThatThrownBy(() -> registry.get("R-R03").validate(new Params(
                Map.of("tradeDropPct", 100, "unsold3mChgPct", 10, "minTradesYearAgo", 30)))).hasMessageContaining("tradeDropPct");
    }
}
