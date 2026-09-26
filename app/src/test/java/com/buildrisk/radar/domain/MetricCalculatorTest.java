package com.buildrisk.radar.domain;

import com.buildrisk.radar.domain.metric.CompanyMetricCalculator;
import com.buildrisk.radar.domain.metric.CompanyMetricCalculator.PeriodFacts;
import com.buildrisk.radar.domain.metric.MetricValue;
import com.buildrisk.radar.domain.metric.RegionMetricCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MetricCalculatorTest {
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
    void 지역_지표_천가구당_미분양과_3개월_변화() {
        var unsold = new TreeMap<String, BigDecimal>(Map.of("202604", new BigDecimal("100"), "202607", new BigDecimal("250")));
        var sale = new TreeMap<String, BigDecimal>(Map.of("202604", new BigDecimal("101.5"), "202607", new BigDecimal("100.0")));
        var jeonse = new TreeMap<String, BigDecimal>(Map.of("202604", new BigDecimal("99"), "202607", new BigDecimal("100")));
        var hh = new TreeMap<String, BigDecimal>(Map.of("2024", new BigDecimal("50000")));
        var vs = RegionMetricCalculator.compute("41110", new RegionMetricCalculator.Series(unsold, sale, jeonse, hh));
        var v = vs.stream().filter(x -> x.period().equals("202607"))
                .collect(Collectors.toMap(MetricValue::metricCode, Function.identity()));
        assertThat(v.get("UNSOLD_PER_1K_HH").value()).isEqualByComparingTo("5");
        assertThat(v.get("UNSOLD_PER_1K_HH").components()).containsEntry("householdsYear", "2024");
        assertThat(v.get("UNSOLD_3M_CHG").value()).isEqualByComparingTo("150");
        assertThat(v.get("PRICE_IDX_3M_CHG").value()).isEqualByComparingTo("-1.5");
        assertThat(v.get("JEONSE_SALE_GAP").value()).isEqualByComparingTo("2.5");
    }

    @Test
    void 미분양_0에서_0은_0퍼센트_0에서_양수는_정의불가() {
        var u = new TreeMap<String, BigDecimal>(Map.of("202601", BigDecimal.ZERO, "202604", BigDecimal.ZERO,
                "202602", BigDecimal.ZERO, "202605", BigDecimal.TEN));
        var e = new TreeMap<String, BigDecimal>();
        var vs = RegionMetricCalculator.compute("1", new RegionMetricCalculator.Series(u, e, e, e));
        var chg = vs.stream().filter(x -> x.metricCode().equals("UNSOLD_3M_CHG"))
                .collect(Collectors.toMap(MetricValue::period, Function.identity()));
        assertThat(chg.get("202604").value()).isEqualByComparingTo("0");
        assertThat(chg.get("202605").status()).isEqualTo("ZERO_DENOM");
    }
}
