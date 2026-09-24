package com.buildrisk.radar.domain.rule;

import com.buildrisk.radar.adapters.common.ApiQuotaService;
import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.domain.disclosure.DisclosureRepository;
import com.buildrisk.radar.domain.metric.MetricRepository;
import com.buildrisk.radar.domain.rule.RuleModels.DisclosureEvent;
import com.buildrisk.radar.domain.rule.RuleModels.MetricPoint;
import com.buildrisk.radar.domain.rule.RuleModels.TargetType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;

/** 규칙 평가용 DB 데이터 소스 */
@Component
public class DbRuleData implements RuleData {
    private final MetricRepository metrics;
    private final DisclosureRepository disclosures;
    private final AppProperties.Batch cfg;

    public DbRuleData(MetricRepository metrics, DisclosureRepository disclosures, AppProperties props) {
        this.metrics = metrics;
        this.disclosures = disclosures;
        this.cfg = props.batch();
    }

    @Override
    public NavigableMap<String, MetricPoint> companyMetric(String corpCode, String metricCode) {
        return metrics.companySeries(corpCode, metricCode);
    }

    @Override
    public NavigableMap<String, MetricPoint> regionMetric(String regionCd, String metricCode) {
        return metrics.regionSeries(regionCd, metricCode);
    }

    @Override
    public List<DisclosureEvent> disclosures(String corpCode, LocalDate since) { return disclosures.events(corpCode, since); }

    @Override
    public int windowSize(TargetType type) {
        return type == TargetType.COMPANY ? cfg.companyWindowQuarters() : cfg.regionWindowMonths();
    }

    @Override
    public LocalDate today() { return ApiQuotaService.today(); }
}
