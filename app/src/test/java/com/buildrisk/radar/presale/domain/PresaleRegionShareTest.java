package com.buildrisk.radar.presale.domain;

import com.buildrisk.radar.metric.domain.MetricPoint;
import com.buildrisk.radar.presale.domain.PresaleModels.PresaleFact;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** R-X02 · 기업 분양 탭이 함께 쓰는 위험 지역 분양 비중 (ADR-032) */
class PresaleRegionShareTest {
    private static PresaleFact f(String region, int units) {
        return new PresaleFact("H" + region + units, "P", "단지", LocalDate.of(2026, 9, 1), region, "지역", region == null ? "SIDO" : "SIGUNGU",
                units, List.of());
    }

    private static MetricPoint unsold(String v) {
        return new MetricPoint("202608", new BigDecimal(v), "OK", Map.of());
    }

    @Test
    void 미분양_지표가_없는_지역과_시도만인_공고는_분모에서_뺀다() {
        Map<String, MetricPoint> latest = Map.of("11110", unsold("7.5"), "26110", unsold("1.2"));
        var s = PresaleRegionShare.of(List.of(f("11110", 600), f("26110", 400), f("99999", 1000), f(null, 500), f("11110", 0)),
                latest::get, BigDecimal.valueOf(5)).orElseThrow();
        assertThat(s.notices()).isEqualTo(2);
        assertThat(s.totalUnits()).isEqualTo(1000);
        assertThat(s.riskUnits()).isEqualTo(600);
        assertThat(s.sharePct()).isEqualByComparingTo("60");
        assertThat(s.excluded()).isEqualTo(1);                  // 99999: 미분양 지표 없음
        assertThat(s.risky()).hasSize(1);
        assertThat(s.risky().get(0).unsold().period()).isEqualTo("202608");
    }

    @Test
    void 셀_공고가_없으면_비어_있다() {
        assertThat(PresaleRegionShare.of(List.of(f("99999", 100), f(null, 10)), r -> null, BigDecimal.ONE)).isEmpty();
        assertThat(PresaleRegionShare.of(List.of(), r -> null, BigDecimal.ONE)).isEmpty();
    }

    @Test
    void 최신_판단_가능_값() {
        var series = new TreeMap<String, MetricPoint>();
        series.put("202606", new MetricPoint("202606", BigDecimal.valueOf(3), "OK", Map.of()));
        series.put("202607", new MetricPoint("202607", null, "MISSING", Map.of()));
        assertThat(PresaleRegionShare.latestOk(series).period()).isEqualTo("202606");
        assertThat(PresaleRegionShare.latestOk(new TreeMap<>())).isNull();
    }
}
