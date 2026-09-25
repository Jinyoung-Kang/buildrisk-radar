package com.buildrisk.radar.batch.jobs;

import com.buildrisk.radar.adapters.common.ApiQuotaService;
import com.buildrisk.radar.adapters.dart.DartClient;
import com.buildrisk.radar.batch.support.QuotaGuard;
import com.buildrisk.radar.batch.support.RunRecorder;
import com.buildrisk.radar.batch.support.SkipRecorder;
import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.common.seed.SeedCatalog;
import com.buildrisk.radar.domain.filing.FilingParser;
import com.buildrisk.radar.domain.filing.FilingRepository;
import com.buildrisk.radar.domain.filing.FilingRepository.Target;
import com.buildrisk.radar.domain.region.AddressRegionMatcher;
import com.buildrisk.radar.domain.region.RegionRepository;
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
 * filingParseJob — 수주·보증 공시 원문 구조화 (ADR-016)
 *   step1 fetch  : 아직 받지 않은 공시의 document.xml → 원문 저장 + 파싱 (항목 하나 = 공시 하나, 청크 안 동시 호출)
 *   step2 reparse: 파서 버전이 낮은 원문을 호출 없이 다시 파싱
 *   step3 link   : 정정 → 원 공시 대체, 해지 → 계약 연결 (현재 수주 잔고 계산의 기준)
 */
@Configuration
public class FilingJobConfig {
    private static final Logger log = LoggerFactory.getLogger(FilingJobConfig.class);

    public record Downloaded(Target target, String html) {}

    @Bean
    Job filingParseJob(JobRepository repo, Step filingFetchStep, Step filingReparseStep, Step filingLinkStep, RunRecorder runs,
                  com.buildrisk.radar.common.cache.JsonCache cache) {
        return new JobBuilder("filingParseJob", repo).listener(runs.collect("DART", cache::invalidateAll))
                .start(filingFetchStep).next(filingReparseStep).next(filingLinkStep).build();
    }

    @Bean
    @StepScope
    ItemReader<Target> filingReader(FilingRepository filings, @Value("#{jobParameters['days']}") Long days) {
        List<Target> plan = filings.pending(ApiQuotaService.today().minusDays(days == null ? 400 : days));
        log.info("공시 원문 수집 대상 {}건", plan.size());
        return new ListItemReader<>(plan);
    }

    @Bean
    @StepScope
    ItemProcessor<Target, Downloaded> filingProcessor(DartClient dart, SkipRecorder skips,
                                                      @Value("#{stepExecution}") StepExecution se,
                                                      @Value("#{jobParameters['maxCalls']}") Long maxCalls) {
        QuotaGuard guard = new QuotaGuard(se, skips, maxCalls == null ? Long.MAX_VALUE : maxCalls);
        return t -> guard.call(t.rceptNo(), () -> new Downloaded(t, dart.document(t.rceptNo()).orElse(null)));
    }

    @Bean
    @StepScope
    ItemWriter<Downloaded> filingWriter(FilingRepository filings, RegionRepository regions, SeedCatalog seed) {
        AddressRegionMatcher matcher = new AddressRegionMatcher(regions.refs(), seed.regionAliases());
        return chunk -> chunk.getItems().forEach(d -> filings.save(d.target(), d.html(), matcher));
    }

    @Bean
    Step filingFetchStep(JobRepository repo, PlatformTransactionManager tm, ItemReader<Target> filingReader,
                         ItemProcessor<Target, Downloaded> filingProcessor, ItemWriter<Downloaded> filingWriter,
                         AppProperties props) {
        var step = new StepBuilder("filingFetchStep", repo).<Target, Downloaded>chunk(20).transactionManager(tm)
                .reader(filingReader).processor(filingProcessor).writer(filingWriter);
        AsyncTaskExecutor executor = MarketJobsConfig.apiExecutor("dart-doc-", props.batch().apiConcurrency());
        return (executor == null ? step : step.taskExecutor(executor)).build();
    }

    @Bean
    Step filingReparseStep(JobRepository repo, PlatformTransactionManager tm, FilingRepository filings,
                           RegionRepository regions, SeedCatalog seed) {
        return new StepBuilder("filingReparseStep", repo).tasklet((contribution, ctx) -> {
            var stale = filings.stale(FilingParser.VERSION);
            if (!stale.isEmpty()) {
                AddressRegionMatcher matcher = new AddressRegionMatcher(regions.refs(), seed.regionAliases());
                stale.forEach(s -> filings.save(s.target(), s.html(), matcher));
                log.info("파서 v{} 로 원문 {}건 다시 파싱 (API 호출 없음)", FilingParser.VERSION, stale.size());
            }
            contribution.incrementWriteCount(stale.size());
            return RepeatStatus.FINISHED;
        }, tm).build();
    }

    @Bean
    Step filingLinkStep(JobRepository repo, PlatformTransactionManager tm, FilingRepository filings) {
        return new StepBuilder("filingLinkStep", repo).tasklet((contribution, ctx) -> {
            contribution.incrementWriteCount(filings.link());
            return RepeatStatus.FINISHED;
        }, tm).build();
    }
}
