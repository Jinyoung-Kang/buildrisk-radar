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

    /** 평가할 최근 기간 수 (기업 분기 · 지역 월) */
    int windowSize(RuleModels.TargetType type);

    LocalDate today();
}
