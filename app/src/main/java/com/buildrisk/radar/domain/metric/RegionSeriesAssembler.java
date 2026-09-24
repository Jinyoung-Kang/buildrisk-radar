package com.buildrisk.radar.domain.metric;

import com.buildrisk.radar.domain.metric.MetricRepository.StatPoint;
import com.buildrisk.radar.domain.region.RegionRef;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 출처 통계를 화면 단위 시군구(level 2)로 모읍니다.
 *   자기 행이 있으면 그대로, 없으면 일반구(level 3) 값을 SUM(미분양·가구 — 모든 하위 구가 있을 때만) 또는 MEAN(지수)
 */
public final class RegionSeriesAssembler {
    private RegionSeriesAssembler() {}

    public static Map<String, RegionMetricCalculator.Series> assemble(List<RegionRef> refs, List<StatPoint> stats) {
        Map<String, List<String>> children = new HashMap<>();
        refs.stream().filter(r -> r.level() == 3 && r.parentCd() != null)
                .forEach(r -> children.computeIfAbsent(r.parentCd(), k -> new ArrayList<>()).add(r.regionCd()));
        // stat_code → region → period → value
        Map<String, Map<String, NavigableMap<String, BigDecimal>>> by = new HashMap<>();
        Map<String, String> aggOf = new HashMap<>();
        for (StatPoint s : stats) {
            aggOf.put(s.statCode(), s.agg());
            by.computeIfAbsent(s.statCode(), k -> new HashMap<>())
                    .computeIfAbsent(s.regionCd(), k -> new TreeMap<>()).put(s.period(), s.value());
        }
        Map<String, RegionMetricCalculator.Series> out = new LinkedHashMap<>();
        for (RegionRef d : refs) {
            if (d.level() != 2) continue;
            List<String> kids = children.getOrDefault(d.regionCd(), List.of());
            out.put(d.regionCd(), new RegionMetricCalculator.Series(
                    pick(by, aggOf, "UNSOLD", d.regionCd(), kids), pick(by, aggOf, "SALE_IDX", d.regionCd(), kids),
                    pick(by, aggOf, "JEONSE_IDX", d.regionCd(), kids), pick(by, aggOf, "HOUSEHOLDS", d.regionCd(), kids)));
        }
        return out;
    }

    private static NavigableMap<String, BigDecimal> pick(Map<String, Map<String, NavigableMap<String, BigDecimal>>> by,
                                                          Map<String, String> aggOf, String stat, String region,
                                                          List<String> kids) {
        Map<String, NavigableMap<String, BigDecimal>> m = by.getOrDefault(stat, Map.of());
        NavigableMap<String, BigDecimal> own = m.get(region);
        if (own != null && !own.isEmpty()) return own;
        NavigableMap<String, BigDecimal> out = new TreeMap<>();
        if (kids.isEmpty()) return out;
        boolean sum = !"MEAN".equals(aggOf.get(stat));
        TreeSet<String> periods = new TreeSet<>();
        kids.forEach(k -> periods.addAll(m.getOrDefault(k, new TreeMap<>()).keySet()));
        for (String p : periods) {
            List<BigDecimal> vals = new ArrayList<>();
            for (String k : kids) {
                BigDecimal v = m.getOrDefault(k, new TreeMap<>()).get(p);
                if (v != null) vals.add(v);
            }
            if (vals.isEmpty() || (sum && vals.size() < kids.size())) continue;
            BigDecimal total = vals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            out.put(p, sum ? total : total.divide(BigDecimal.valueOf(vals.size()), MathContext.DECIMAL64));
        }
        return out;
    }
}
