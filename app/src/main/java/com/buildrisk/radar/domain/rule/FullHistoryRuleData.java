package com.buildrisk.radar.domain.rule;

import com.buildrisk.radar.domain.rule.RuleModels.DisclosureEvent;
import com.buildrisk.radar.domain.rule.RuleModels.TargetType;
import com.buildrisk.radar.metric.domain.MetricPoint;

import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;

/**
 * 평가 창만 넓힌 RuleData — 경보 성적표(ADR-023)가 규칙을 전체 기간에 대해 다시 평가할 때.
 * 운영 경보(risk.alert)는 최근 창만 보므로 옛 사건 앞의 판정이 없고, 창을 넓히면 운영 목록에 옛 경보가 섞이기 때문에 따로 둡니다.
 */
public final class FullHistoryRuleData implements RuleData {
    private static final int ALL = 400;
    private final RuleData delegate;

    public FullHistoryRuleData(RuleData delegate) { this.delegate = delegate; }

    @Override public NavigableMap<String, MetricPoint> companyMetric(String c, String m) { return delegate.companyMetric(c, m); }
    @Override public NavigableMap<String, MetricPoint> regionMetric(String r, String m) { return delegate.regionMetric(r, m); }
    @Override public List<DisclosureEvent> disclosures(String c, LocalDate since) { return delegate.disclosures(c, since); }
    @Override public List<RuleModels.ContractFact> contracts(String c, LocalDate since) { return delegate.contracts(c, since); }
    @Override public List<RuleModels.GuaranteeFact> guarantees(String c, LocalDate since) { return delegate.guarantees(c, since); }
    @Override public int windowSize(TargetType type) { return ALL; }
    @Override public LocalDate today() { return delegate.today(); }
}
