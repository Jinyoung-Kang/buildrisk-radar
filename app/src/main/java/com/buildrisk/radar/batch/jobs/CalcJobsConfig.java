package com.buildrisk.radar.batch.jobs;

import com.buildrisk.radar.batch.support.BatchKeys;
import com.buildrisk.radar.batch.support.RunRecorder;
import com.buildrisk.radar.domain.account.AccountStandardizer;
import com.buildrisk.radar.domain.account.FsRepository.StdRow;
import com.buildrisk.radar.domain.account.FsRepository;
import com.buildrisk.radar.domain.metric.CompanyMetricCalculator;
import com.buildrisk.radar.domain.metric.MetricRepository;
import com.buildrisk.radar.domain.metric.MetricValue;
import com.buildrisk.radar.domain.metric.RegionMetricCalculator;
import com.buildrisk.radar.domain.metric.RegionSeriesAssembler;
import com.buildrisk.radar.domain.metric.StandardizeService;
import com.buildrisk.radar.domain.rule.AlertService;
import com.buildrisk.radar.domain.rule.DbRuleData;
import com.buildrisk.radar.domain.rule.RuleEvaluator;
import com.buildrisk.radar.domain.rule.RuleModels.RuleDefinition;
import com.buildrisk.radar.domain.rule.RuleModels.TargetType;
import com.buildrisk.radar.domain.rule.RuleRegistry;
import com.buildrisk.radar.domain.rule.RuleRepository;
import com.buildrisk.radar.domain.universe.CompanyRepository;
import com.buildrisk.radar.region.data.RegionRepository;
import com.buildrisk.radar.region.data.SiteLocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.support.ListItemReader;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 그림 2 — 표준화 → 지표 → 규칙 평가
 *   standardizeMetricJob: standardizeStep(fs_raw→fs_std) → companyMetricStep → pfMetricStep → regionMetricStep → siteLocateStep
 *                         (Step 5개, 재시작하면 실패한 Step 부터만 다시 실행)
 *   ruleEvalJob         : 사용 중인 규칙(최신 버전) × 대상 → 경보 UPSERT + 근거 (chunk 100)
 * 두 Job 모두 ops.calc_run 을 남기고, 완료되면 조회 캐시 세대를 올립니다.
 */
@Configuration
public class CalcJobsConfig {
    private static final Logger log = LoggerFactory.getLogger(CalcJobsConfig.class);

    // ---------------------------------------------------------------- standardizeMetricJob

    @Bean
    Job standardizeMetricJob(JobRepository repo, Step standardizeStep, Step companyMetricStep, Step pfMetricStep, Step regionMetricStep,
                             Step siteLocateStep, RunRecorder runs) {
        return new JobBuilder("standardizeMetricJob", repo).listener(runs.calc("METRIC"))
                .start(standardizeStep).next(companyMetricStep).next(pfMetricStep).next(regionMetricStep).next(siteLocateStep).build();
    }

    @Bean
    @StepScope
    ListItemReader<String> targetCorpReader(CompanyRepository companies) {
        return new ListItemReader<>(companies.targetCorpCodes());
    }

    public record StdResult(String corpCode, List<StdRow> rows) {}

    @Bean
    Step standardizeStep(JobRepository repo, PlatformTransactionManager tm, ListItemReader<String> targetCorpReader,
                         StandardizeService svc, FsRepository fs, CalcRunHolder calcRun) {
        AccountStandardizer[] std = new AccountStandardizer[1];
        return new StepBuilder("standardizeStep", repo).<String, StdResult>chunk(10).transactionManager(tm)
                .reader(targetCorpReader)
                .processor(corp -> {
                    if (std[0] == null) std[0] = svc.standardizer();
                    return new StdResult(corp, svc.standardize(corp, std[0]));
                })
                .writer(chunk -> {
                    // 표준화 결과에도 계산 실행을 남김 — 근거 계보가 '어느 계산이 만든 값인지' 보이게 (VERIFICATION 134)
                    for (StdResult r : chunk.getItems()) fs.replaceStd(r.corpCode(), r.rows(), calcRun.id());
                })
                .listener(new StepExecutionListener() {
                    @Override
                    public void beforeStep(StepExecution se) { std[0] = null; }
                })
                .build();
    }

    @Bean
    @StepScope
    ItemProcessor<String, List<MetricValue>> companyMetricProcessor(StandardizeService svc, FsRepository fs) {
        return corp -> CompanyMetricCalculator.compute(corp, svc.facts(fs.std(corp)));
    }

    @Bean
    @StepScope
    ListItemReader<String> metricCorpReader(CompanyRepository companies) {
        return new ListItemReader<>(companies.targetCorpCodes());
    }

    @Bean
    Step companyMetricStep(JobRepository repo, PlatformTransactionManager tm, ListItemReader<String> metricCorpReader,
                           ItemProcessor<String, List<MetricValue>> companyMetricProcessor, MetricRepository metrics,
                           CalcRunHolder calcRun) {
        return new StepBuilder("companyMetricStep", repo).<String, List<MetricValue>>chunk(20).transactionManager(tm)
                .reader(metricCorpReader).processor(companyMetricProcessor)
                .writer(chunk -> {
                    for (List<MetricValue> vs : chunk.getItems()) {
                        if (!vs.isEmpty()) metrics.replaceCompanyMetrics(vs.get(0).targetKey(), vs, calcRun.id());
                    }
                })
                .build();
    }

    /** PF 우발채무 지표 (ADR-024) — 기업 지표를 다시 쓴 뒤 정기보고서 PF 합계 ÷ 자본총계 */
    @Bean
    Step pfMetricStep(JobRepository repo, PlatformTransactionManager tm, MetricRepository metrics) {
        return new StepBuilder("pfMetricStep", repo).tasklet((contribution, chunk) -> {
            UUID run = RunRecorder.runId(chunk.getStepContext().getStepExecution(), BatchKeys.CALC_RUN_ID);
            int n = metrics.insertPfMetrics(run);
            contribution.incrementWriteCount(n);
            log.info("PF 우발채무 지표 {}건", n);
            return RepeatStatus.FINISHED;
        }, tm).build();
    }

    /** 사업장 위치 (ADR-026) — 공급계약 주소의 법정동 · 지번을 실거래와 대조 (호출 없음, 실거래 사전이 넓어지므로 매번 전체) */
    @Bean
    Step siteLocateStep(JobRepository repo, PlatformTransactionManager tm, SiteLocationRepository sites) {
        return new StepBuilder("siteLocateStep", repo).tasklet((contribution, chunk) -> {
            var counts = sites.relocateAll();
            contribution.incrementWriteCount(counts.values().stream().mapToInt(Integer::intValue).sum());
            log.info("사업장 위치 해석 {}", counts);
            return RepeatStatus.FINISHED;
        }, tm).build();
    }

    @Bean
    Step regionMetricStep(JobRepository repo, PlatformTransactionManager tm, RegionRepository regions,
                          MetricRepository metrics) {
        return new StepBuilder("regionMetricStep", repo).tasklet((contribution, chunk) -> {
            UUID run = RunRecorder.runId(chunk.getStepContext().getStepExecution(), BatchKeys.CALC_RUN_ID);
            var series = RegionSeriesAssembler.assemble(regions.refs(), metrics.allRegionStats());
            List<MetricValue> all = new ArrayList<>();
            series.forEach((cd, s) -> all.addAll(RegionMetricCalculator.compute(cd, s)));
            metrics.replaceRegionMetrics(all, run);
            contribution.incrementWriteCount(all.size());
            log.info("지역 지표 {}건 ({}개 시군구)", all.size(), series.size());
            return RepeatStatus.FINISHED;
        }, tm).build();
    }

    /** Step 안에서 현재 Job 의 calc_run_id 를 꺼내는 step-scope 도우미 */
    @Bean
    @StepScope
    CalcRunHolder calcRunHolder(@Value("#{jobExecutionContext['" + BatchKeys.CALC_RUN_ID + "']}") String id) {
        return new CalcRunHolder(UUID.fromString(id));
    }

    public static class CalcRunHolder {
        private final UUID id;

        CalcRunHolder(UUID id) { this.id = id; }

        public UUID id() { return id; }
    }

    // ---------------------------------------------------------------- ruleEvalJob

    public record RuleTarget(RuleDefinition rule, String targetKey) {}

    @Bean
    Job ruleEvalJob(JobRepository repo, Step ruleEvalStep, Step ruleScopeStep, RunRecorder runs) {
        return new JobBuilder("ruleEvalJob", repo).listener(runs.calc("RULE"))
                .start(ruleEvalStep).next(ruleScopeStep).build();
    }

    /**
     * 평가 대상(켜진 규칙 × 지금의 대상)에서 빠진 열린 경보를 닫음 — 다시 평가되지 않아 영구히 OPEN 이었음 (VERIFICATION 127).
     * 대상 목록이 비면(적재 중 등) 그 종류는 건너뜀: 빈 목록을 '모두 빠짐'으로 보지 않음
     */
    @Bean
    Step ruleScopeStep(JobRepository repo, PlatformTransactionManager tm, RuleRepository rules, CompanyRepository companies,
                       RegionRepository regions, AlertService alerts) {
        return new StepBuilder("ruleScopeStep", repo).tasklet((contribution, chunk) -> {
            List<String> ruleCodes = rules.latestEnabled().stream().map(RuleDefinition::code).toList();
            List<String> corps = companies.targetCorpCodes();
            List<String> regionCds = regions.refs().stream().filter(r -> r.level() == 2).map(r -> r.regionCd()).toList();
            contribution.incrementWriteCount(alerts.closeOutOfScope(ruleCodes, corps, regionCds));
            return RepeatStatus.FINISHED;
        }, tm).build();
    }

    @Bean
    @StepScope
    ListItemReader<RuleTarget> ruleTargetReader(RuleRepository rules, CompanyRepository companies, RegionRepository regions) {
        List<String> corps = companies.targetCorpCodes();
        List<String> regionCds = regions.refs().stream().filter(r -> r.level() == 2).map(r -> r.regionCd()).toList();
        List<RuleTarget> items = new ArrayList<>();
        for (RuleDefinition r : rules.latestEnabled()) {
            for (String t : r.targetType() == TargetType.COMPANY ? corps : regionCds) items.add(new RuleTarget(r, t));
        }
        return new ListItemReader<>(items);
    }

    @Bean
    Step ruleEvalStep(JobRepository repo, PlatformTransactionManager tm, ListItemReader<RuleTarget> ruleTargetReader,
                      RuleRegistry registry, DbRuleData data, AlertService alerts, CalcRunHolder calcRun) {
        return new StepBuilder("ruleEvalStep", repo).<RuleTarget, AlertService.Outcome>chunk(100).transactionManager(tm)
                .reader(ruleTargetReader)
                .processor(t -> {
                    RuleEvaluator ev = registry.get(t.rule().code());
                    return new AlertService.Outcome(t.rule(), ev.singleActive(), t.targetKey(),
                            ev.evaluate(t.rule(), t.targetKey(), data));
                })
                .writer(chunk -> {
                    for (AlertService.Outcome o : chunk.getItems()) alerts.apply(o, calcRun.id());
                })
                .build();
    }
}
