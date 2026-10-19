package com.buildrisk.radar.alert.app;

import com.buildrisk.radar.alert.domain.RuleData;
import com.buildrisk.radar.alert.domain.RuleModels.TargetType;
import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.common.Kst;
import com.buildrisk.radar.filing.data.DisclosureRepository;
import com.buildrisk.radar.filing.data.ExposureRepository;
import com.buildrisk.radar.filing.domain.FilingModels.ContractFact;
import com.buildrisk.radar.filing.domain.FilingModels.DisclosureEvent;
import com.buildrisk.radar.filing.domain.FilingModels.GuaranteeFact;
import com.buildrisk.radar.metric.data.MetricRepository;
import com.buildrisk.radar.metric.domain.MetricPoint;
import com.buildrisk.radar.presale.data.PresaleRepository;
import com.buildrisk.radar.presale.domain.PresaleModels.PresaleFact;
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
    private final ExposureRepository exposure;
    private final PresaleRepository presales;

    public DbRuleData(MetricRepository metrics, DisclosureRepository disclosures, AppProperties props,
                      ExposureRepository exposure, PresaleRepository presales) {
        this.exposure = exposure;
        this.presales = presales;
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
    public List<ContractFact> contracts(String corpCode, LocalDate since) { return exposure.contracts(corpCode, since); }

    @Override
    public List<GuaranteeFact> guarantees(String corpCode, LocalDate since) { return exposure.guarantees(corpCode, since); }

    @Override
    public List<PresaleFact> presales(String corpCode, LocalDate since) { return presales.facts(corpCode, since); }

    @Override
    public int windowSize(TargetType type) {
        return type == TargetType.COMPANY ? cfg.companyWindowQuarters() : cfg.regionWindowMonths();
    }

    @Override
    public LocalDate today() { return Kst.today(); }
}
