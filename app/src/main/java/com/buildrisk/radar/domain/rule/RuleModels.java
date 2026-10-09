package com.buildrisk.radar.domain.rule;

import java.util.List;
import java.util.Map;

public final class RuleModels {
    private RuleModels() {}

    public enum TargetType { COMPANY, REGION }

    /** risk.rule 한 버전 */
    public record RuleDefinition(String code, int version, TargetType targetType, String nameKo, String description,
                                 Map<String, Object> params, String severity, boolean enabled) {}

    /** 규칙이 특정 시점에서 참이 된 결과 — evidence 는 JSON 으로 저장 (근거 없는 경보 0, FR-503) */
    public record Finding(String asOf, String title, String message, Map<String, Object> evidence) {}

    /**
     * 한 대상 평가 결과.
     *   evaluatedAsOfs: 이번에 평가한 시점들 (자동 CLOSED 판정용)
     *   latestAsOf    : 판단할 수 있는 가장 최근 시점 — singleActive 규칙은 이 시점 경보만 OPEN
     *   decided       : 거짓이면 판단할 수 있는 시점이 하나도 없음(값이 없거나 MISSING · ZERO_DENOM …) — 열린 경보를 그대로 둠
     */
    public record Evaluation(List<Finding> findings, List<String> evaluatedAsOfs, String latestAsOf, boolean decided) {
        public Evaluation(List<Finding> findings, List<String> evaluatedAsOfs, String latestAsOf) {
            this(findings, evaluatedAsOfs, latestAsOf, true);
        }
    }
}
