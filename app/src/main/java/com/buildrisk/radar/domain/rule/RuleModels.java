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
    /** 현재(정정 반영·해지 제외) 수주 계약 — regionCd 는 화면 단위 시군구, 매핑 못 하면 null */
    public record ContractFact(String rceptNo, LocalDate rceptDt, String name, BigDecimal amount, String regionCd,
                               String regionName, String regionMatch) {}

    /** 채무보증 결정 (정정 반영) — totalBalance 는 공시 시점 회사 전체 보증 잔액 */
    public record GuaranteeFact(String rceptNo, LocalDate rceptDt, String debtor, BigDecimal amount, BigDecimal equity,
                                BigDecimal totalBalance, BigDecimal pfAmount, boolean balanceIsLimit, BigDecimal unusedLimit) {
        public GuaranteeFact(String rceptNo, LocalDate rceptDt, String debtor, BigDecimal amount, BigDecimal equity,
                             BigDecimal totalBalance, BigDecimal pfAmount) {
            this(rceptNo, rceptDt, debtor, amount, equity, totalBalance, pfAmount, false, null);
        }
    }

    public record Finding(String asOf, String title, String message, Map<String, Object> evidence) {}

    /**
     * 한 대상 평가 결과.
     *   evaluatedAsOfs: 이번에 평가한 시점들 (자동 CLOSED 판정용)
     *   latestAsOf    : 가장 최근 평가 시점 — singleActive 규칙은 이 시점 경보만 OPEN
     */
    public record Evaluation(List<Finding> findings, List<String> evaluatedAsOfs, String latestAsOf) {}
}
