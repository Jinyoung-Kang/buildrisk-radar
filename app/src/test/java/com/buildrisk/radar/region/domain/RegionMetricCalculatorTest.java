package com.buildrisk.radar.region.domain;

import com.buildrisk.radar.metric.domain.MetricValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RegionMetricCalculatorTest {
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

    @Test
    void 청약홈_롤링_세대는_가구로_나누고_0과_MISSING_ZERO_DENOM_을_구분하며_미래_기간을_만들지_않는다() {
        // ADR-032: 기준월(≤ 이번 달) 키의 롤링 두 시계열만 들어옴 — 입주예정월(미래) 키를 넣으면 2028 기간 지표가 생겼을 것
        var e = new TreeMap<String, BigDecimal>();
        var hh = new TreeMap<String, BigDecimal>(Map.of("2024", new BigDecimal("40000")));
        var supply = new TreeMap<String, BigDecimal>(Map.of("202609", new BigDecimal("600"), "202610", BigDecimal.ZERO));
        var movein = new TreeMap<String, BigDecimal>(Map.of("202610", new BigDecimal("1000")));
        var vs = RegionMetricCalculator.compute("41110", new RegionMetricCalculator.Series(e, e, e, hh, e, e, e, supply, movein));
        var by = vs.stream().collect(Collectors.toMap(v -> v.metricCode() + "@" + v.period(), Function.identity()));
        assertThat(by.get("PRESALE_12M_PER_1K_HH@202609").value()).isEqualByComparingTo("15");          // 600 / 40,000 × 1000
        assertThat(by.get("PRESALE_12M_PER_1K_HH@202609").components()).containsEntry("householdsYear", "2024");
        assertThat(by.get("PRESALE_12M_PER_1K_HH@202610").value()).isEqualByComparingTo("0");           // 공고 없음 = 0 (OK)
        assertThat(by.get("PRESALE_12M_PER_1K_HH@202610").status()).isEqualTo("OK");
        assertThat(by.get("MOVEIN_12M_AHEAD_PER_1K_HH@202610").value()).isEqualByComparingTo("25");
        assertThat(by).doesNotContainKey("MOVEIN_12M_AHEAD_PER_1K_HH@202609");                         // 창이 안 찬 달은 집계에 행이 없음
        assertThat(vs).allSatisfy(v -> assertThat(v.period()).isLessThanOrEqualTo("202610"));
        assertThat(vs).noneMatch(v -> v.metricCode().startsWith("UNSOLD") || v.metricCode().startsWith("TRADE"));

        var noHh = RegionMetricCalculator.compute("1", new RegionMetricCalculator.Series(e, e, e, e, e, e, e, supply, e));
        assertThat(noHh).allSatisfy(v -> assertThat(v.status()).isEqualTo("MISSING"));
        var zeroHh = RegionMetricCalculator.compute("1", new RegionMetricCalculator.Series(e, e, e,
                new TreeMap<>(Map.of("2024", BigDecimal.ZERO)), e, e, e, supply, e));
        assertThat(zeroHh).allSatisfy(v -> assertThat(v.status()).isEqualTo("ZERO_DENOM"));
    }
}
