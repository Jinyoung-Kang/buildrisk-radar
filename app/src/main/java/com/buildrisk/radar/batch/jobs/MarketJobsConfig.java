package com.buildrisk.radar.batch.jobs;

import com.buildrisk.radar.adapters.common.ApiQuotaService;
import com.buildrisk.radar.adapters.datagokr.RtmsClient;
import com.buildrisk.radar.batch.support.BatchKeys;
import com.buildrisk.radar.batch.support.QuotaGuard;
import com.buildrisk.radar.batch.support.RunRecorder;
import com.buildrisk.radar.batch.support.SkipRecorder;
import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.domain.market.AptTradeRepository;
import com.buildrisk.radar.domain.market.AptTradeRepository.Fetched;
import com.buildrisk.radar.domain.market.AptTradeRepository.Target;
import com.buildrisk.radar.domain.market.TradeWindow;
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
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * aptTradeJob — 국토부 아파트 매매 실거래 (ADR-015)
 *   step1 fetch    : (시군구, 계약월) 하나 = 항목 하나. 청크 안 항목은 가상 스레드로 동시 호출(apiConcurrency),
 *                    재시작 지점은 수집 원장(apt_trade_fetch)에서 도출 — 멈춘 곳부터 이어받음 (ADR-008)
 *   step2 aggregate: 화면 단위 시군구 × 월 거래 건수 · 해제 건수 · ㎡당 중위가 → region_stat → 지표 Job 이 전년 대비 계산
 */
@Configuration
public class MarketJobsConfig {
    private static final Logger log = LoggerFactory.getLogger(MarketJobsConfig.class);

    @Bean
    Job aptTradeJob(JobRepository repo, Step aptTradeFetchStep, Step aptTradeAggregateStep, RunRecorder runs,
                  com.buildrisk.radar.common.cache.JsonCache cache) {
        return new JobBuilder("aptTradeJob", repo).listener(runs.collect("RTMS", cache::invalidateAll))
                .start(aptTradeFetchStep).next(aptTradeAggregateStep).build();
    }

    @Bean
    @StepScope
    ItemReader<Target> aptTradeReader(AptTradeRepository trades, AppProperties props,
                                      @Value("#{jobParameters['months']}") Long months) {
        TradeWindow w = TradeWindow.of(YearMonth.now(ApiQuotaService.KST),
                months == null ? props.dataGoKr().tradeMonths() : months.intValue());
        List<Target> plan = trades.pending(w.fromYm(), w.toYm(), w.refreshFromYm(), ApiQuotaService.today());
        log.info("실거래 수집 대상 {}건 ({}~{}, {} 이후 재수집)", plan.size(), w.fromYm(), w.toYm(), w.refreshFromYm());
        return new ListItemReader<>(plan);
    }

    @Bean
    @StepScope
    ItemProcessor<Target, Fetched> aptTradeProcessor(RtmsClient rtms, SkipRecorder skips,
                                                     @Value("#{stepExecution}") StepExecution se,
                                                     @Value("#{jobParameters['maxCalls']}") Long maxCalls) {
        QuotaGuard guard = new QuotaGuard(se, skips, maxCalls == null ? Long.MAX_VALUE : maxCalls);
        return t -> guard.call(t.lawdCd() + ":" + t.dealYm(),
                () -> new Fetched(t.lawdCd(), t.dealYm(), rtms.trades(t.lawdCd(), TradeWindow.parse(t.dealYm()))));
    }

    @Bean
    @StepScope
    ItemWriter<Fetched> aptTradeWriter(AptTradeRepository trades,
                                       @Value("#{jobExecutionContext['" + BatchKeys.COLLECT_RUN_ID + "']}") String runId) {
        UUID run = UUID.fromString(runId);
        return chunk -> trades.replace(List.copyOf(chunk.getItems()), run);
    }

    @Bean
    Step aptTradeFetchStep(JobRepository repo, PlatformTransactionManager tm, ItemReader<Target> aptTradeReader,
                           ItemProcessor<Target, Fetched> aptTradeProcessor, ItemWriter<Fetched> aptTradeWriter,
                           AppProperties props) {
        var step = new StepBuilder("aptTradeFetchStep", repo).<Target, Fetched>chunk(20).transactionManager(tm)
                .reader(aptTradeReader).processor(aptTradeProcessor).writer(aptTradeWriter);
        AsyncTaskExecutor executor = apiExecutor("rtms-", props.batch().apiConcurrency());
        return (executor == null ? step : step.taskExecutor(executor)).build();
    }

    @Bean
    Step aptTradeAggregateStep(JobRepository repo, PlatformTransactionManager tm, AptTradeRepository trades,
                               AppProperties props) {
        return new StepBuilder("aptTradeAggregateStep", repo).tasklet((contribution, ctx) -> {
            TradeWindow w = TradeWindow.of(YearMonth.now(ApiQuotaService.KST), props.dataGoKr().tradeMonths());
            UUID run = UUID.fromString(contribution.getStepExecution().getJobExecution().getExecutionContext()
                    .getString(BatchKeys.COLLECT_RUN_ID));
            int n = trades.aggregate(w.fromYm(), w.completeToYm(), run);
            contribution.incrementWriteCount(n);
            log.info("실거래 월 집계 {}행 (~{})", n, w.completeToYm());
            return RepeatStatus.FINISHED;
        }, tm).build();
    }

    /**
     * 외부 API 호출용 청크 안 동시 처리 실행기 — 가상 스레드, 동시 수 제한. 호출 간격(Throttle)·일일 상한(QuotaGuard)은
     * 스레드 안전하게 그대로 지켜지고, 쓰기(Writer)는 청크 단위로 한 트랜잭션입니다. 1 이하면 순차.
     */
    static AsyncTaskExecutor apiExecutor(String prefix, int concurrency) {
        if (concurrency <= 1) return null;
        SimpleAsyncTaskExecutor ex = new SimpleAsyncTaskExecutor(prefix);
        ex.setVirtualThreads(true);
        ex.setConcurrencyLimit(concurrency);
        return ex;
    }
}
