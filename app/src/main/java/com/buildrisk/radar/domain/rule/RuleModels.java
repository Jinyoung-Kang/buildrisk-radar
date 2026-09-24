package com.buildrisk.radar.domain.rule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class RuleModels {
    private RuleModels() {}

    public enum TargetType { COMPANY, REGION }

    /** risk.rule 한 버전 */
    public record RuleDefinition(String code, int version, TargetType targetType, String nameKo, String description,
                                 Map<String, Object> params, String severity, boolean enabled) {}

    /** 지표 한 점 (company_metric · region_metric) */
    public record MetricPoint(String period, BigDecimal value, String status, Map<String, Object> components) {
        public boolean ok() { return value != null && "OK".equals(status); }
    }

    public record DisclosureEvent(String rceptNo, String reportNm, LocalDate rceptDt, String eventType, String keyword) {}

    /** 규칙이 특정 시점에서 참이 된 결과 — evidence 는 JSON 으로 저장 (근거 없는 경보 0, FR-503) */
    public record Finding(String asOf, String title, String message, Map<String, Object> evidence) {}

    /**
     * 한 대상 평가 결과.
     *   evaluatedAsOfs: 이번에 평가한 시점들 (자동 CLOSED 판정용)
     *   latestAsOf    : 가장 최근 평가 시점 — singleActive 규칙은 이 시점 경보만 OPEN
     */
    public record Evaluation(List<Finding> findings, List<String> evaluatedAsOfs, String latestAsOf) {}
}
