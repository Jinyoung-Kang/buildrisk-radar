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
}
