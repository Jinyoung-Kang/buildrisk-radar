package com.buildrisk.radar.company.domain;

import com.buildrisk.radar.company.domain.CompanyMetricCalculator.PeriodFacts;
import com.buildrisk.radar.company.domain.FsModels.StdRow;
import com.buildrisk.radar.metric.domain.MetricValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyMetricCalculatorTest {
    static Map<String, BigDecimal> m(Object... kv) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], new BigDecimal(String.valueOf(kv[i + 1])));
        return out;
    }

    static PeriodFacts f(String pk, Map<String, BigDecimal> point, Map<String, BigDecimal> cum, Map<String, BigDecimal> qtr) {
        return new PeriodFacts(pk, "CFS", "20260814000123", "11012", point, cum, qtr);
    }

    static Map<String, MetricValue> at(List<MetricValue> vs, String pk) {
        return vs.stream().filter(v -> v.period().equals(pk)).collect(Collectors.toMap(MetricValue::metricCode, Function.identity()));
    }

    @Test
    void 부채비율_차입금의존도_이자보상배율과_전년동기_차이() {
        var facts = Map.of(
                "2025Q2", f("2025Q2", m("TOTAL_LIABILITIES", 250, "TOTAL_EQUITY", 100, "TOTAL_ASSETS", 350,
                        "SHORT_BORROWINGS", 35), m(), m()),
                "2026Q2", f("2026Q2", m("TOTAL_LIABILITIES", 400, "TOTAL_EQUITY", 100, "TOTAL_ASSETS", 500,
                        "SHORT_BORROWINGS", 50, "BONDS", 50), m("REVENUE", 1000, "OPERATING_CASH_FLOW", -100),
                        m("OPERATING_INCOME", 8, "INTEREST_EXPENSE", 10, "OPERATING_CASH_FLOW", -40)));
        var v = at(CompanyMetricCalculator.compute("00000001", facts), "2026Q2");
        assertThat(v.get("DEBT_RATIO").value()).isEqualByComparingTo("400");
        assertThat(v.get("DEBT_RATIO_YOY").value()).isEqualByComparingTo("150");
        assertThat(v.get("BORROWING_DEP").value()).isEqualByComparingTo("20");
        assertThat(v.get("INTEREST_COVERAGE").value()).isEqualByComparingTo("0.8");
        assertThat(v.get("OCF_MARGIN").value()).isEqualByComparingTo("-10");
        assertThat(v.get("OCF_QTR").value()).isEqualByComparingTo("-40");
        assertThat(v.get("DEBT_RATIO").components()).containsEntry("rceptNo", "20260814000123");
    }

    @Test
    void 자본잠식_분모0_계정없음은_값_대신_상태코드() {
        var facts = Map.of("2026Q1", f("2026Q1", m("TOTAL_LIABILITIES", 100, "TOTAL_EQUITY", -5, "CURRENT_ASSETS", 1,
                "CURRENT_LIABILITIES", 0), m(), m("OPERATING_INCOME", 5)));
        var v = at(CompanyMetricCalculator.compute("00000001", facts), "2026Q1");
        assertThat(v.get("DEBT_RATIO").status()).isEqualTo("NEG_EQUITY");
        assertThat(v.get("DEBT_RATIO").value()).isNull();
        assertThat(v.get("CURRENT_RATIO").status()).isEqualTo("ZERO_DENOM");
        assertThat(v.get("INTEREST_COVERAGE").status()).isEqualTo("MISSING");
        assertThat(v.get("BORROWING_DEP").status()).isEqualTo("MISSING");
    }

    @Test
    void 분기_이자비용이_음수면_부호가_뒤집힌_배율_대신_불일치() {
        // 1분기 '이자지급' 620 · 반기 '이자지급(영업)' 477 → 누적 차분 -143 (실측 사례). 영업이익이 양수인데 배율이 음수가 되면 오경보
        var facts = Map.of("2025Q2", f("2025Q2", m(), m(), m("OPERATING_INCOME", 140, "INTEREST_EXPENSE", -143)));
        var v = at(CompanyMetricCalculator.compute("00000001", facts), "2025Q2");
        assertThat(v.get("INTEREST_COVERAGE").status()).isEqualTo("INCONSISTENT");
        assertThat(v.get("INTEREST_COVERAGE").value()).isNull();
    }

    @Test
    void 같은_입력이면_같은_결과() {   // FR-501 재실행 시 같은 값
        var facts = Map.of("2026Q1", f("2026Q1", m("TOTAL_LIABILITIES", 3, "TOTAL_EQUITY", 7), m(), m()));
        assertThat(CompanyMetricCalculator.compute("x", facts)).isEqualTo(CompanyMetricCalculator.compute("x", facts));
    }

    @Test
    void 기간마다_연결_우선_없으면_별도_분기_차분은_출처에서_뺌() {
        var rows = List.of(
                new StdRow("c", "2026Q2", "CFS", "TOTAL_ASSETS", "POINT", new BigDecimal("500"), "자산총계", "BS", "R1", "11012"),
                new StdRow("c", "2026Q2", "OFS", "TOTAL_ASSETS", "POINT", new BigDecimal("300"), "자산총계", "BS", "R2", "11012"),
                new StdRow("c", "2026Q2", "CFS", "REVENUE", "QTR", new BigDecimal("40"), "누적 차분", null, "R1", "11012"),
                new StdRow("c", "2026Q1", "OFS", "REVENUE", "CUM", new BigDecimal("70"), "매출액", "IS", "R0", "11013"));
        var f = CompanyMetricCalculator.facts(rows);
        assertThat(f.get("2026Q2").fsDiv()).isEqualTo("CFS");
        assertThat(f.get("2026Q2").point()).containsEntry("TOTAL_ASSETS", new BigDecimal("500"));
        assertThat(f.get("2026Q2").qtr()).containsEntry("REVENUE", new BigDecimal("40"));
        assertThat(f.get("2026Q2").sources()).containsOnlyKeys("TOTAL_ASSETS").containsEntry("TOTAL_ASSETS", "BS:자산총계");
        assertThat(f.get("2026Q2").rceptNo()).isEqualTo("R1");
        assertThat(f.get("2026Q1").fsDiv()).isEqualTo("OFS");
        assertThat(f.get("2026Q1").cum()).containsEntry("REVENUE", new BigDecimal("70"));
    }
}
