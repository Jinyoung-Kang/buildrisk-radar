package com.buildrisk.radar.domain;

import com.buildrisk.radar.domain.rule.Evidence;
import com.buildrisk.radar.domain.rule.Params;
import com.buildrisk.radar.domain.rule.RuleData;
import com.buildrisk.radar.domain.rule.RuleModels.DisclosureEvent;
import com.buildrisk.radar.domain.rule.RuleModels.MetricPoint;
import com.buildrisk.radar.domain.rule.RuleModels.RuleDefinition;
import com.buildrisk.radar.domain.rule.RuleModels.TargetType;
import com.buildrisk.radar.domain.rule.RuleRegistry;
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

class RuleEvaluatorTest {
    final RuleRegistry registry = new RuleRegistry();

    /** 메모리 RuleData — 테스트마다 지표 시계열을 직접 넣음 */
    static class Mem implements RuleData {
        final Map<String, NavigableMap<String, MetricPoint>> metrics = new HashMap<>();
        final List<DisclosureEvent> events = new ArrayList<>();

        Mem put(String metric, String period, String value) {
            metrics.computeIfAbsent(metric, k -> new TreeMap<>()).put(period, new MetricPoint(period,
                    value == null ? null : new BigDecimal(value), value == null ? "MISSING" : "OK",
                    Map.of("rceptNo", "2026081400" + period.substring(period.length() - 4), "reprtCode", "11012",
                            "numerator", Map.of("amount", 1), "denominator", Map.of("amount", 2))));
            return this;
        }

        @Override public NavigableMap<String, MetricPoint> companyMetric(String c, String m) { return metrics.getOrDefault(m, new TreeMap<>()); }
        @Override public NavigableMap<String, MetricPoint> regionMetric(String r, String m) { return metrics.getOrDefault(m, new TreeMap<>()); }
        @Override public List<DisclosureEvent> disclosures(String c, LocalDate since) {
            return events.stream().filter(e -> !e.rceptDt().isBefore(since)).toList();
        }
        @Override public int windowSize(TargetType t) { return 8; }
        @Override public LocalDate today() { return LocalDate.of(2026, 9, 24); }
    }

    /** 근거 계보(ADR-027) 계약: 규칙이 선언한 근거 지표는 seed 파라미터로 평가할 때 실제로 읽는 지표여야 함 (오타 · 낡은 선언 방지) */
    @Test
    @SuppressWarnings("unchecked")
    void 규칙이_선언한_근거_지표는_실제로_읽는_지표() {
        var seed = com.buildrisk.radar.common.seed.SeedCatalog.at(java.nio.file.Path.of("../seed"));
        List<Map<String, Object>> rules = (List<Map<String, Object>>) seed.yaml("rules.yml").get("rules");
        int declared = 0;
        for (Map<String, Object> r : rules) {
            var ev = registry.get((String) r.get("code"));
            java.util.Set<String> read = new java.util.HashSet<>();
            var d = new Mem() {
                @Override public NavigableMap<String, MetricPoint> companyMetric(String c, String m) { read.add(m); return super.companyMetric(c, m); }
                @Override public NavigableMap<String, MetricPoint> regionMetric(String x, String m) { read.add(m); return super.regionMetric(x, m); }
            };
            ev.evaluate(rule(ev.code(), ev.targetType(), (Map<String, Object>) r.get("params")), "t", d);
            assertThat(read).as(ev.code()).containsAll(ev.evidenceMetrics());
            declared += ev.evidenceMetrics().isEmpty() ? 0 : 1;
        }
        assertThat(declared).as("지표 기반 규칙 수").isEqualTo(7);
    }

    static RuleDefinition rule(String code, TargetType t, Map<String, Object> params) {
        return new RuleDefinition(code, 1, t, code, "", params, "HIGH", true);
    }

    @Test
    void R_C02_이자보상배율_2분기_연속일_때만_참() {
        var d = new Mem().put("DEBT_RATIO", "2025Q4", "100").put("DEBT_RATIO", "2026Q1", "100").put("DEBT_RATIO", "2026Q2", "100")
                .put("INTEREST_COVERAGE", "2025Q4", "0.5").put("INTEREST_COVERAGE", "2026Q1", "1.2")
                .put("INTEREST_COVERAGE", "2026Q2", "0.9");
        var r = rule("R-C02", TargetType.COMPANY, Map.of("threshold", 1.0, "consecutive", 2));
        assertThat(registry.get("R-C02").evaluate(r, "c", d).findings()).isEmpty();

        d.put("INTEREST_COVERAGE", "2026Q1", "0.8");
        var ev = registry.get("R-C02").evaluate(r, "c", d);
        assertThat(ev.findings()).extracting(f -> f.asOf()).containsExactly("2026Q1", "2026Q2");
        assertThat(ev.latestAsOf()).isEqualTo("2026Q2");
        var evidence = ev.findings().get(1).evidence();
        assertThat(evidence).containsKeys("condition", "params", "observations", "sources", "message");
        assertThat((List<?>) evidence.get("observations")).hasSize(2);
        assertThat((List<?>) evidence.get("sources")).isNotEmpty();   // 근거 없는 경보 0
    }

    @Test
    void R_C02_영업손실_분기는_뜻없는_음수_배율_대신_영업손실로_쓴다() {
        // 분기 이자비용 5원 · 영업손실 302억 → -60억 배 (실측). 숫자는 observations 에 그대로 두고 문장만 읽을 수 있게
        var d = new Mem().put("DEBT_RATIO", "2026Q1", "1").put("DEBT_RATIO", "2026Q2", "1")
                .put("INTEREST_COVERAGE", "2026Q1", "0.5").put("INTEREST_COVERAGE", "2026Q2", "-6049063301.8");
        var r = rule("R-C02", TargetType.COMPANY, Map.of("threshold", 1.0, "consecutive", 2));
        var f = registry.get("R-C02").evaluate(r, "c", d).findings().getLast();
        assertThat(f.message()).startsWith("2026Q1 0.5배, 2026Q2 영업손실로").doesNotContain("6,049");
    }

    @Test
    void 금액은_조_억_만원_단위로_읽는다() {
        assertThat(Evidence.won(new BigDecimal("2125000000000"))).isEqualTo("2조 1,250억원");
        assertThat(Evidence.won(new BigDecimal("2000000000000"))).isEqualTo("2조원");
        assertThat(Evidence.won(new BigDecimal("684400000000"))).isEqualTo("6,844억원");
        assertThat(Evidence.won(new BigDecimal("-35000000"))).isEqualTo("-3,500만원");
        assertThat(Evidence.won(BigDecimal.ZERO)).isEqualTo("0원");
        assertThat(Evidence.won(null)).isEqualTo("-");
    }

    @Test
    void R_R01_기저가_작으면_증감률과_함께_늘어난_호수를_쓴다() {
        var d = new Mem().put("UNSOLD_UNITS", "202511", "842").put("UNSOLD_PER_1K_HH", "202511", "8.1");
        d.metrics.computeIfAbsent("UNSOLD_3M_CHG", k -> new TreeMap<>()).put("202511", new MetricPoint("202511",
                new BigDecimal("20950"), "OK", Map.of("unsold", 842, "unsold3mAgo", 4, "period3mAgo", "202508")));
        var r = rule("R-R01", TargetType.REGION, Map.of("unsold3mChgPct", 50, "unsoldPer1kHh", 2, "minUnsoldUnits", 100));
        assertThat(registry.get("R-R01").evaluate(r, "41110", d).findings().getFirst().message())
                .isEqualTo("202511 미분양 842호로 3개월 전(4호)보다 838호(20,950%) 늘었고, 천 가구당 8.1호입니다.");
    }

    @Test
    void R_C03_영업현금흐름_3분기_연속_음수일_때만_참이고_금액은_억원_단위() {
        var d = new Mem().put("DEBT_RATIO", "2025Q4", "1").put("DEBT_RATIO", "2026Q1", "1").put("DEBT_RATIO", "2026Q2", "1")
                .put("OCF_QTR", "2025Q4", "-106700000000").put("OCF_QTR", "2026Q1", "-33500000000").put("OCF_QTR", "2026Q2", "56600000000");
        var r = rule("R-C03", TargetType.COMPANY, Map.of("consecutive", 3));
        assertThat(registry.get("R-C03").evaluate(r, "c", d).findings()).isEmpty();          // 마지막 분기 유입
        d.put("OCF_QTR", "2026Q2", "-56600000000");
        assertThat(registry.get("R-C03").evaluate(r, "c", d).findings().getLast().message())
                .isEqualTo("분기 영업활동현금흐름이 2025Q4 -1,067억원, 2026Q1 -335억원, 2026Q2 -566억원으로 3개 분기 연속 음수입니다.");
    }

    @Test
    void R_C06_PF_보증이나_12개월_내_만기가_자기자본_대비_임계를_넘으면_참() {
        var d = new Mem();
        java.util.function.BiConsumer<String, Object[]> put = (metric, v) -> d.metrics.computeIfAbsent(metric, k -> new TreeMap<>())
                .put((String) v[0], new MetricPoint((String) v[0], new BigDecimal((String) v[1]), "OK", Map.of(
                        "rceptNo", "20260814003287", "reconciled", true,
                        "numerator", Map.of("amount", v[2]), "denominator", Map.of("amount", v[3]))));
        // 태영건설 2026 반기 실측 규모: PF 보증 2조 3,544억 · 12개월 내 만기 1조 3,122억, 자기자본 7,409억으로 가정
        put.accept("PF_GUARANTEE_TO_EQUITY", new Object[]{"2026Q2", "317.8", 2_354_379_000_000L, 740_900_000_000L});
        put.accept("PF_DUE_12M_TO_EQUITY", new Object[]{"2026Q2", "177.1", 1_312_155_000_000L, 740_900_000_000L});
        put.accept("PF_BRIDGE_SHARE", new Object[]{"2026Q2", "34.2", 804_782_000_000L, 2_354_379_000_000L});
        var r = rule("R-C06", TargetType.COMPANY, Map.of("guaranteeToEquityPct", 100, "due12mToEquityPct", 50));
        var f = registry.get("R-C06").evaluate(r, "c", d).findings().getFirst();
        assertThat(f.message()).isEqualTo("2026Q2 부동산 PF 보증 2조 3,544억원으로 자기자본(7,409억원)의 318%, "
                + "12개월 안에 만기가 돌아오는 PF 대출이 1조 3,122억원(자기자본의 177%)입니다. 보증 중 브릿지론 비중 34.2%.");
        // 둘 다 임계 아래면 거짓
        var low = rule("R-C06", TargetType.COMPANY, Map.of("guaranteeToEquityPct", 400, "due12mToEquityPct", 200));
        assertThat(registry.get("R-C06").evaluate(low, "c", d).findings()).isEmpty();
        // 12개월 내 만기만 넘어도 참
        var dueOnly = rule("R-C06", TargetType.COMPANY, Map.of("guaranteeToEquityPct", 400, "due12mToEquityPct", 100));
        assertThat(registry.get("R-C06").evaluate(dueOnly, "c", d).findings()).hasSize(1);
    }

    @Test
    void R_C02_중간_분기가_비면_연속이_아니다() {
        var d = new Mem().put("DEBT_RATIO", "2026Q2", "1").put("INTEREST_COVERAGE", "2025Q4", "0.2")
                .put("INTEREST_COVERAGE", "2026Q2", "0.2");
        var r = rule("R-C02", TargetType.COMPANY, Map.of("threshold", 1.0, "consecutive", 2));
        assertThat(registry.get("R-C02").evaluate(r, "c", d).findings()).isEmpty();
    }

    @Test
    void R_C01_부채비율과_전년대비_둘_다_넘어야_참() {
        var d = new Mem().put("DEBT_RATIO", "2026Q2", "320").put("DEBT_RATIO_YOY", "2026Q2", "40");
        var r = rule("R-C01", TargetType.COMPANY, Map.of("debtRatioThreshold", 300, "yoyIncreasePp", 50));
        assertThat(registry.get("R-C01").evaluate(r, "c", d).findings()).isEmpty();
        d.put("DEBT_RATIO_YOY", "2026Q2", "60");
        assertThat(registry.get("R-C01").evaluate(r, "c", d).findings()).hasSize(1);
    }

    @Test
    void R_C04_창_안의_중대_공시만_건별로() {
        var d = new Mem();
        d.events.add(new DisclosureEvent("20260910000001", "기업구조개선(워크아웃)신청", LocalDate.of(2026, 9, 10), "REHAB", "워크아웃"));
        d.events.add(new DisclosureEvent("20260911000002", "단일판매ㆍ공급계약체결", LocalDate.of(2026, 9, 11), "CONTRACT", "공급계약"));
        d.events.add(new DisclosureEvent("20260701000003", "부도발생", LocalDate.of(2026, 7, 1), "DEFAULT", "부도발생"));
        var r = rule("R-C04", TargetType.COMPANY, Map.of("eventTypes", List.of("REHAB", "DEFAULT"), "windowDays", 30));
        var ev = registry.get("R-C04").evaluate(r, "c", d);
        assertThat(ev.findings()).extracting(f -> f.asOf()).containsExactly("20260910000001");
        assertThat(registry.get("R-C04").singleActive()).isFalse();
    }

    @Test
    void R_R01_최소_호수_미만이면_증가율이_커도_거짓() {
        var d = new Mem().put("UNSOLD_UNITS", "202607", "40").put("UNSOLD_3M_CHG", "202607", "300")
                .put("UNSOLD_PER_1K_HH", "202607", "5");
        var r = rule("R-R01", TargetType.REGION, Map.of("unsold3mChgPct", 50, "unsoldPer1kHh", 2, "minUnsoldUnits", 100));
        assertThat(registry.get("R-R01").evaluate(r, "41110", d).findings()).isEmpty();
        d.put("UNSOLD_UNITS", "202607", "400");
        assertThat(registry.get("R-R01").evaluate(r, "41110", d).findings()).hasSize(1);
    }

    @Test
    void R_R02_가격_3개월_연속_하락과_미분양_증가() {
        var d = new Mem().put("UNSOLD_UNITS", "202607", "10").put("UNSOLD_3M_CHG", "202607", "5")
                .put("PRICE_IDX_3M_CHG", "202605", "-0.1").put("PRICE_IDX_3M_CHG", "202606", "-0.3")
                .put("PRICE_IDX_3M_CHG", "202607", "-0.2");
        var r = rule("R-R02", TargetType.REGION, Map.of("consecutive", 3));
        assertThat(registry.get("R-R02").evaluate(r, "41110", d).findings()).hasSize(1);
    }

    @Test
    void 파라미터_검증() {   // PUT /rules → 400 RULE_PARAM_INVALID
        assertThatThrownBy(() -> registry.get("R-C02").validate(new Params(Map.of("threshold", 0, "consecutive", 2))))
                .hasMessageContaining("0 보다 커야");
        assertThatThrownBy(() -> registry.get("R-C02").validate(new Params(Map.of("threshold", 1, "consecutive", 20))))
                .hasMessageContaining("1~8");
        assertThatThrownBy(() -> registry.get("R-C04").validate(new Params(Map.of("eventTypes", List.of("X"), "windowDays", 30))))
                .hasMessageContaining("알 수 없는 이벤트 유형");
    }
}
