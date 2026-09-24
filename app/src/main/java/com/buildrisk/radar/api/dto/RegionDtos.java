package com.buildrisk.radar.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class RegionDtos {
    private RegionDtos() {}

    public record RegionRow(String regionCd, String name, String fullName, String sidoCd, String sidoName,
                            BigDecimal value, String status, int alertCount) {}

    public record RegionList(String metric, String unit, String period, List<String> periods, List<RegionRow> items,
                             String calcRunId, String disclaimer) {}

    public record Point(String period, BigDecimal value) {}

    public record StatSeries(String code, String nameKo, String unit, String source, List<Point> points) {}

    public record MetricPoint(String period, BigDecimal value, String status, Map<String, Object> components) {}

    public record MetricSeries(String code, String nameKo, String unit, String formula, List<MetricPoint> points) {}

    public record AlertBrief(long alertId, String ruleCode, String severity, String asOf, String title, String status) {}

    public record RegionSeries(String regionCd, String name, String fullName, String sidoName, List<String> children,
                               List<StatSeries> stats, List<MetricSeries> metrics, List<AlertBrief> alerts,
                               List<Map<String, Object>> sourceCodes, String disclaimer) {}
}
