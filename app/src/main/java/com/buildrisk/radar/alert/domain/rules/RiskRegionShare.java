package com.buildrisk.radar.alert.domain.rules;

import com.buildrisk.radar.filing.domain.FilingModels.ContractFact;
import com.buildrisk.radar.metric.domain.MetricPoint;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.function.Function;

/**
 * 위험 지역 수주 비중 — 시군구로 매핑되고 금액이 있는 계약 중 '그 지역의 최신(판단 가능한) 천 가구당 미분양 ≥ 기준' 계약의 금액 비중.
 * R-X01 과 기업 화면(수주 · 보증 탭)이 같은 계산을 씀 — 화면이 따로 계산하면 기간 · 기준이 달라 같은 화면에 다른 숫자가 보였음 (VERIFICATION 187)
 *
 * @param contracts 계산에 쓴 계약 수(시군구 매핑 · 금액 있음)
 * @param risky     기준 이상 지역의 계약과 그 지역 지표 (입력 순서)
 */
public record RiskRegionShare(int contracts, BigDecimal totalAmount, BigDecimal riskAmount, BigDecimal sharePct, List<Risky> risky) {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public record Risky(ContractFact contract, MetricPoint unsold) {}

    /** 계산할 계약이 없으면 비어 있음. latestUnsold: 지역 코드 → 그 지역의 최신 판단 가능 지표(없으면 null) */
    public static Optional<RiskRegionShare> of(List<ContractFact> all, Function<String, MetricPoint> latestUnsold, BigDecimal threshold) {
        List<ContractFact> cs = all.stream().filter(c -> c.regionCd() != null && c.amount() != null && c.amount().signum() > 0).toList();
        if (cs.isEmpty()) return Optional.empty();
        Map<String, MetricPoint> latest = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO, risk = BigDecimal.ZERO;
        List<Risky> risky = new ArrayList<>();
        for (ContractFact c : cs) {
            MetricPoint m = latest.computeIfAbsent(c.regionCd(), latestUnsold);
            total = total.add(c.amount());
            if (m != null && m.value().compareTo(threshold) >= 0) {
                risk = risk.add(c.amount());
                risky.add(new Risky(c, m));
            }
        }
        return Optional.of(new RiskRegionShare(cs.size(), total, risk, risk.multiply(HUNDRED).divide(total, MathContext.DECIMAL64),
                List.copyOf(risky)));
    }

    /** 시계열의 가장 최근 판단 가능(ok) 값 */
    public static MetricPoint latestOk(NavigableMap<String, MetricPoint> series) {
        for (var e : series.descendingMap().values()) if (e.ok()) return e;
        return null;
    }
}
