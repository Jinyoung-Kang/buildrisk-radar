package com.buildrisk.radar.batch.jobs;

import com.buildrisk.radar.adapters.common.ApiQuotaService;
import com.buildrisk.radar.adapters.datagokr.StockPriceClient;
import com.buildrisk.radar.adapters.datagokr.StockPriceClient.Daily;
import com.buildrisk.radar.batch.support.BatchKeys;
import com.buildrisk.radar.batch.support.QuotaGuard;
import com.buildrisk.radar.batch.support.RunRecorder;
import com.buildrisk.radar.batch.support.SkipRecorder;
import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.domain.market.StockRepository;
import com.buildrisk.radar.domain.market.StockRepository.Target;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** stockPriceJob — 유니버스 상장사 일별 시세 (ADR-017). 종목 하나 = 항목 하나, 마지막 저장일 다음 날부터 */
@Configuration
public class StockJobConfig {

    @Bean
    Job stockPriceJob(JobRepository repo, Step stockPriceStep, RunRecorder runs,
                  com.buildrisk.radar.common.cache.JsonCache cache) {
        return new JobBuilder("stockPriceJob", repo).listener(runs.collect("FSC", cache::invalidateAll)).start(stockPriceStep).build();
    }

    @Bean
    @StepScope
    ItemReader<Target> stockReader(StockRepository stocks, AppProperties props, @Value("#{jobParameters['years']}") Long years) {
        LocalDate today = ApiQuotaService.today();
        LocalDate start = today.minusYears(years == null ? props.dataGoKr().stockYears() : years);
        return new ListItemReader<>(stocks.targets(start).stream().filter(t -> !t.from().isAfter(today)).toList());
    }

    @Bean
    @StepScope
    ItemProcessor<Target, List<Daily>> stockProcessor(StockPriceClient client, SkipRecorder skips,
                                                      @Value("#{stepExecution}") StepExecution se,
                                                      @Value("#{jobParameters['maxCalls']}") Long maxCalls) {
        QuotaGuard guard = new QuotaGuard(se, skips, maxCalls == null ? Long.MAX_VALUE : maxCalls);
        LocalDate end = ApiQuotaService.today().plusDays(1);      // endBasDt 는 미포함
        return t -> guard.call(t.stockCode(), () -> client.daily(t.stockCode(), t.from(), end));
    }

    @Bean
    @StepScope
    ItemWriter<List<Daily>> stockWriter(StockRepository stocks,
                                        @Value("#{jobExecutionContext['" + BatchKeys.COLLECT_RUN_ID + "']}") String runId) {
        UUID run = UUID.fromString(runId);
        return chunk -> {
            List<Daily> all = new ArrayList<>();
            chunk.getItems().forEach(all::addAll);
            stocks.upsert(all, run);
        };
    }

    @Bean
    Step stockPriceStep(JobRepository repo, PlatformTransactionManager tm, ItemReader<Target> stockReader,
                        ItemProcessor<Target, List<Daily>> stockProcessor, ItemWriter<List<Daily>> stockWriter,
                        AppProperties props) {
        var step = new StepBuilder("stockPriceStep", repo).<Target, List<Daily>>chunk(10).transactionManager(tm)
                .reader(stockReader).processor(stockProcessor).writer(stockWriter);
        AsyncTaskExecutor executor = MarketJobsConfig.apiExecutor("stock-", props.batch().apiConcurrency());
        return (executor == null ? step : step.taskExecutor(executor)).build();
    }
}
