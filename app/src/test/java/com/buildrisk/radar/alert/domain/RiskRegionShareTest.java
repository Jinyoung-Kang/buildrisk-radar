package com.buildrisk.radar.alert.domain;

import com.buildrisk.radar.alert.domain.rules.RiskRegionShare;
import com.buildrisk.radar.filing.domain.FilingModels.ContractFact;
import com.buildrisk.radar.metric.domain.MetricPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** 위험 지역 수주 비중 — R-X01 과 기업 화면이 같이 쓰는 계산 (VERIFICATION 187) */
class RiskRegionShareTest {
    private static ContractFact c(String no, String region, long amount) {
        return new ContractFact(no, LocalDate.of(2026, 6, 1), "계약" + no, amount == 0 ? null : BigDecimal.valueOf(amount), region, region, region == null ? "NONE" : "SIGUNGU");
    }

    private static MetricPoint m(String period, double v, String status) {
        return new MetricPoint(period, BigDecimal.valueOf(v), status, Map.of());
    }

    @Test
    void 시군구로_매핑되고_금액이_있는_계약만_세고_기준_이상_지역의_금액_비중() {
        var share = RiskRegionShare.of(List.of(c("1", "A", 300), c("2", "B", 100), c("3", null, 900), c("4", "A", 0)),
                r -> r.equals("A") ? m("202606", 6.0, "OK") : m("202606", 1.0, "OK"), BigDecimal.valueOf(5)).orElseThrow();
        assertThat(share.contracts()).isEqualTo(2);
        assertThat(share.totalAmount()).isEqualByComparingTo("400");
        assertThat(share.riskAmount()).isEqualByComparingTo("300");
        assertThat(share.sharePct()).isEqualByComparingTo("75");
        assertThat(share.risky()).extracting(x -> x.contract().rceptNo()).containsExactly("1");
    }

    @Test
    void 계산할_계약이_없으면_비고_지표는_최신의_판단_가능한_값() {
        assertThat(RiskRegionShare.of(List.of(c("1", null, 100)), r -> null, BigDecimal.ONE)).isEmpty();
        var series = new TreeMap<String, MetricPoint>(Map.of("202604", m("202604", 7.0, "OK"), "202605", m("202605", 0, "MISSING")));
        assertThat(RiskRegionShare.latestOk(series).period()).isEqualTo("202604");
        assertThat(RiskRegionShare.latestOk(new TreeMap<>())).isNull();
    }
}
