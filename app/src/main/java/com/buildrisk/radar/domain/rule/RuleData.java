package com.buildrisk.radar.domain.rule;

import com.buildrisk.radar.domain.rule.RuleModels.DisclosureEvent;
import com.buildrisk.radar.domain.rule.RuleModels.MetricPoint;

import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;

/** 규칙이 읽는 데이터 — 배치에서는 DB, 테스트에서는 메모리 구현. */
public interface RuleData {
    /** 기간 → 지표 값 (기업: 2026Q2, 지역: 202607) */
    NavigableMap<String, MetricPoint> companyMetric(String corpCode, String metricCode);

    NavigableMap<String, MetricPoint> regionMetric(String regionCd, String metricCode);

    List<DisclosureEvent> disclosures(String corpCode, LocalDate since);

    /** since 이후 접수된 현재 수주 계약 (ADR-016) */
    default List<RuleModels.ContractFact> contracts(String corpCode, LocalDate since) { return List.of(); }

    /** since 이후 채무보증 결정 (정정된 원 공시 제외, 접수일 순) */
    default List<RuleModels.GuaranteeFact> guarantees(String corpCode, LocalDate since) { return List.of(); }

    /** 평가할 최근 기간 수 (기업 분기 · 지역 월) */
    int windowSize(RuleModels.TargetType type);

    LocalDate today();
}
