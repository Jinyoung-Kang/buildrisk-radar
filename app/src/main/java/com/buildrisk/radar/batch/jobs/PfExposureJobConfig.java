package com.buildrisk.radar.batch.jobs;

import com.buildrisk.radar.adapters.dart.DartClient;
import com.buildrisk.radar.batch.support.QuotaGuard;
import com.buildrisk.radar.batch.support.RunRecorder;
import com.buildrisk.radar.batch.support.SkipRecorder;
import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.common.seed.SeedCatalog;
import com.buildrisk.radar.filing.data.PfExposureRepository.Fetched;
import com.buildrisk.radar.filing.data.PfExposureRepository.Target;
import com.buildrisk.radar.filing.data.PfExposureRepository;
import com.buildrisk.radar.filing.domain.PfExposureParser;
import com.buildrisk.radar.region.data.RegionRepository;
import com.buildrisk.radar.region.domain.AddressRegionMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.support.ListItemReader;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

/**
 * pfExposureJob — 정기보고서의 부동산 PF 우발채무 구조화 (ADR-024)
 *   step1 fetch  : 아직 받지 않은 정기보고서(기업 · 분기마다 최신 제출본)의 document.xml 에서 '우발부채 등에 관한 사항' 구간만
 *                  스트리밍으로 꺼내 보관 + 파싱 (본문 10~15 MB 를 통째로 메모리에 올리지 않음, 청크 안 동시 호출)
 *   step2 reparse: 파서 버전이 낮은 보관 구간을 호출 없이 다시 파싱
 * fromPeriod(기본 2023Q4): 금감원 주석공시 모범사례(2024.1)가 적용을 권고한 2023년도 재무제표(사업보고서)부터 — 그 전 서식은 회사마다 달라 구조화하지 않음
 */
@Configuration
public class PfExposureJobConfig {
    private static final Logger log = LoggerFactory.getLogger(PfExposureJobConfig.class);
    public static final String DEFAULT_FROM = "2023Q4";
    static final String START = "D-0-11-2-0", END = "D-0-11-3-0";

    @Bean
    Job pfExposureJob(JobRepository repo, Step pfFetchStep, Step pfReparseStep, RunRecorder runs) {
        return new JobBuilder("pfExposureJob", repo).listener(runs.collect("DART"))
                .start(pfFetchStep).next(pfReparseStep).build();
    }

    @Bean
    @StepScope
    ItemReader<Target> pfReader(PfExposureRepository pf, @Value("#{jobParameters['fromPeriod']}") String from) {
        List<Target> plan = pf.pending(from == null ? DEFAULT_FROM : from);
        log.info("PF 우발채무 정기보고서 수집 대상 {}건", plan.size());
        return new ListItemReader<>(plan);
    }

    @Bean
    @StepScope
    ItemProcessor<Target, Fetched> pfProcessor(DartClient dart, SkipRecorder skips, @Value("#{stepExecution}") StepExecution se,
                                               @Value("#{jobParameters['maxCalls']}") Long maxCalls) {
        QuotaGuard guard = new QuotaGuard(se, skips, maxCalls == null ? Long.MAX_VALUE : maxCalls);
        return t -> guard.call(t.rceptNo(), () -> {
            var section = dart.documentSection(t.rceptNo(), START, END);
            // 문서 없음(013 · 014)과 구간 없음을 구분하기 어렵지만 둘 다 '값 없음 + 사유'로 남김
            return section.map(s -> new Fetched(t, s.html(), s.scannedBytes(), s.truncated(), false))
                    .orElseGet(() -> new Fetched(t, null, 0, false, false));
        });
    }

    @Bean
    @StepScope
    ItemWriter<Fetched> pfWriter(PfExposureRepository pf, RegionRepository regions, SeedCatalog seed,
                                 @Value("#{stepExecution}") StepExecution se) {
        AddressRegionMatcher matcher = new AddressRegionMatcher(regions.refs(), seed.regionAliases());
        long job = se.getJobExecutionId();
        return chunk -> chunk.getItems().forEach(f -> pf.save(f, matcher, job));
    }

    @Bean
    Step pfFetchStep(JobRepository repo, PlatformTransactionManager tm, ItemReader<Target> pfReader,
                     ItemProcessor<Target, Fetched> pfProcessor, ItemWriter<Fetched> pfWriter, AppProperties props) {
        var step = new StepBuilder("pfFetchStep", repo).<Target, Fetched>chunk(10).transactionManager(tm)
                .reader(pfReader).processor(pfProcessor).writer(pfWriter);
        AsyncTaskExecutor executor = MarketJobsConfig.apiExecutor("dart-pf-", props.batch().apiConcurrency());
        return (executor == null ? step : step.taskExecutor(executor)).build();
    }

    @Bean
    Step pfReparseStep(JobRepository repo, PlatformTransactionManager tm, PfExposureRepository pf,
                       RegionRepository regions, SeedCatalog seed) {
        return new StepBuilder("pfReparseStep", repo).tasklet((contribution, ctx) -> {
            var stale = pf.stale(PfExposureParser.VERSION);
            if (!stale.isEmpty()) {
                AddressRegionMatcher matcher = new AddressRegionMatcher(regions.refs(), seed.regionAliases());
                stale.forEach(s -> pf.save(new Fetched(s.target(), s.html(), s.scannedBytes(), s.truncated(), false), matcher, null));
                log.info("PF 파서 v{} 로 보관 구간 {}건 다시 파싱 (API 호출 없음)", PfExposureParser.VERSION, stale.size());
            }
            contribution.incrementWriteCount(stale.size());
            return RepeatStatus.FINISHED;
        }, tm).build();
    }
}
