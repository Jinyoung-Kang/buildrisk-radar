package com.buildrisk.radar.presale.domain;

import com.buildrisk.radar.metric.domain.MetricPoint;
import com.buildrisk.radar.presale.domain.PresaleModels.PresaleFact;

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
 * 위험 지역 분양 비중 (ADR-032) — 시군구로 풀리고 세대가 있는 공고 중, 그 지역의 최신(판단 가능한) 천 가구당 미분양이 있는 공고의
 * 세대를 분모로, 그 값이 기준 이상인 지역 공고의 세대 비중. 미분양 지표가 없는 지역의 공고는 분모에서 뺍니다(excluded).
 * R-X02 와 기업 화면(분양 탭)이 같은 계산을 씀 — R-X01 의 RiskRegionShare 와 같은 원칙(VERIFICATION 187).
 * 컨소시엄은 지분을 몰라 세대를 나누지 않습니다(각 시공사에 그대로 — 과대 가능, 화면에 표시).
 *
 * @param notices  분모에 든 공고 수 · totalUnits 그 세대 합
 * @param excluded 시군구로 풀렸지만 그 지역 미분양 지표가 없어 뺀 공고 수
 * @param risky    기준 이상 지역의 공고와 그 지역 지표 (입력 순서)
 */
public record PresaleRegionShare(int notices, long totalUnits, long riskUnits, BigDecimal sharePct, int excluded, List<Risky> risky) {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public record Risky(PresaleFact fact, MetricPoint unsold) {}

    /** 셀 공고가 없으면 비어 있음. latestUnsold: 지역 코드 → 그 지역의 최신 판단 가능 지표(없으면 null) */
    public static Optional<PresaleRegionShare> of(List<PresaleFact> all, Function<String, MetricPoint> latestUnsold, BigDecimal threshold) {
        Map<String, Optional<MetricPoint>> latest = new HashMap<>();
        long total = 0, risk = 0;
        int counted = 0, excluded = 0;
        List<Risky> risky = new ArrayList<>();
        for (PresaleFact f : all) {
            if (f.regionCd() == null || f.units() <= 0) continue;
            MetricPoint m = latest.computeIfAbsent(f.regionCd(), r -> Optional.ofNullable(latestUnsold.apply(r))).orElse(null);
            if (m == null) {
                excluded++;
                continue;
            }
            counted++;
            total += f.units();
            if (m.value().compareTo(threshold) >= 0) {
                risk += f.units();
                risky.add(new Risky(f, m));
            }
        }
        if (counted == 0) return Optional.empty();
        BigDecimal pct = BigDecimal.valueOf(risk).multiply(HUNDRED).divide(BigDecimal.valueOf(total), MathContext.DECIMAL64);
        return Optional.of(new PresaleRegionShare(counted, total, risk, pct, excluded, List.copyOf(risky)));
    }

    /** 시계열의 가장 최근 판단 가능(ok) 값 — RiskRegionShare.latestOk 와 같은 규칙 */
    public static MetricPoint latestOk(NavigableMap<String, MetricPoint> series) {
        for (var e : series.descendingMap().values()) if (e.ok()) return e;
        return null;
    }
}
