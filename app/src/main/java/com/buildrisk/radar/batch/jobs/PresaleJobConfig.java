package com.buildrisk.radar.batch.jobs;

import com.buildrisk.radar.adapters.applyhome.ApplyhomeClient;
import com.buildrisk.radar.adapters.applyhome.ApplyhomeDatasetEmptyException;
import com.buildrisk.radar.adapters.common.UpstreamException;
import com.buildrisk.radar.adapters.seed.SeedCatalog;
import com.buildrisk.radar.batch.support.BatchKeys;
import com.buildrisk.radar.batch.support.QuotaGuard;
import com.buildrisk.radar.batch.support.RunRecorder;
import com.buildrisk.radar.batch.support.SkipRecorder;
import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.common.Kst;
import com.buildrisk.radar.presale.data.PresaleRepository;
import com.buildrisk.radar.presale.data.PresaleRepository.ModelSet;
import com.buildrisk.radar.presale.data.PresaleRepository.ModelTarget;
import com.buildrisk.radar.presale.data.PresaleRepository.MonthTarget;
import com.buildrisk.radar.presale.data.PresaleRepository.NoticeMonth;
import com.buildrisk.radar.presale.data.PresaleRepository.RemainderMonth;
import com.buildrisk.radar.presale.domain.BuilderNameMatcher;
import com.buildrisk.radar.presale.domain.PresaleModels;
import com.buildrisk.radar.presale.domain.PresaleModels.Dataset;
import com.buildrisk.radar.presale.domain.PresaleModels.Remainder;
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

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * presaleSyncJob — 청약홈 분양 공고 (ADR-032)
 *   1 notice    : (APT 공고, 모집공고 월) 하나 = 항목 하나. 원장에 없는 달 + 최근 3개월(이번 달 포함, 오늘 안 받은 달)
 *   2 remainder : 잔여세대 공고, 같은 월 창. 데이터셋 전체가 빈 응답이면 그 달만 건너뜀 — 화면 표시용이라 뒤 단계를 막지 않음(224)
 *   3 link      : 모든 공고의 주소 → 시군구, 시공사 → 기업, 잔여세대 → 원 공고 (호출 없음, 매번 전체 — 멱등)
 *   4 aggregate : 화면 단위 시군구 × 월 시계열 4개 → region_stat (지표 Job 이 천 가구당으로 나눔)
 *   5 model     : 공고가 바뀌었거나 새 공고의 주택형 (공고당 호출 1건 — 처음 수집이 가장 많아 마지막에 둠: 한도에 걸려도 지표는 오늘 것)
 * 모든 단계는 DB 상태에서 할 일을 다시 읽으므로(ADR-008) 멈춘 실행을 이어받으면 처음부터 다시 돌되 남은 것만 부릅니다.
 * maxCalls 는 이 실행 전체의 항목(달 · 공고) 상한 — 단계마다가 아니라 합계 (remaining).
 */
@Configuration
public class PresaleJobConfig {
    private static final Logger log = LoggerFactory.getLogger(PresaleJobConfig.class);

    /** 외부 API 를 부르는 단계 — 항목 하나 = 호출(쪽이 여럿이면 그 이상) */
    private static final List<String> API_STEPS = List.of("presaleNoticeStep", "presaleRemainderStep", "presaleModelStep");

    /**
     * 이번 실행에서 남은 항목 수 — maxCalls 에서 앞서 끝난 API 단계의 읽은 항목 수를 뺌. 앞 단계가 상한으로 멈췄으면 Job 이 멈춰
     * 뒤 단계는 돌지 않고, 잔여세대의 빈 데이터셋은 달마다 한 번씩 부르며 건너뛰므로 끝난 단계의 읽은 수 = 부른 항목 수. (@JobScope 빈은 청크 안 동시 처리 스레드에서 범위가 없어 쓰지 않음)
     */
    static long remaining(StepExecution se, Long maxCalls) {
        if (maxCalls == null) return Long.MAX_VALUE;
        long used = se.getJobExecution().getStepExecutions().stream()
                .filter(s -> !s.getStepName().equals(se.getStepName()) && API_STEPS.contains(s.getStepName()))
                .mapToLong(StepExecution::getReadCount).sum();
        return Math.max(0, maxCalls - used);
    }

    @Bean
    Job presaleSyncJob(JobRepository repo, Step presaleNoticeStep, Step presaleRemainderStep, Step presaleLinkStep,
                       Step presaleAggregateStep, Step presaleModelStep, RunRecorder runs) {
        return new JobBuilder("presaleSyncJob", repo).listener(runs.collect("PRESALE"))
                .start(presaleNoticeStep).next(presaleRemainderStep).next(presaleLinkStep).next(presaleAggregateStep)
                .next(presaleModelStep).build();
    }

    static int fromYm(AppProperties props, String from) {
        return PresaleModels.fromParam(from == null ? props.applyhome().fromYm() : from);
    }

    private static List<MonthTarget> months(PresaleRepository presales, Dataset ds, int fromYm) {
        YearMonth now = YearMonth.now(Kst.ZONE);
        int refresh = PresaleModels.ym(now.minusMonths(PresaleModels.REFRESH_MONTHS - 1));
        List<MonthTarget> plan = presales.pendingMonths(ds, fromYm, PresaleModels.ym(now), refresh, Kst.today());
        log.info("청약홈 {} 수집 대상 {}개월 ({}~{}, {} 이후 재수집)", ds, plan.size(), fromYm, PresaleModels.ym(now), refresh);
        return plan;
    }

    /** 전에 공고가 있던 달이 0건 — 원천 이상으로 보고 바꾸지 않음 (VERIFICATION 128 과 같은 보호, 사라진 공고 표시가 한꺼번에 찍히지 않게) */
    private static void guardEmpty(MonthTarget t, int got) {
        if (got == 0 && t.previousTotal() != null && t.previousTotal() > 0) {
            throw new UpstreamException(ApplyhomeClient.PROVIDER, t.dataset() + " " + t.ym() + ": 전에 " + t.previousTotal()
                    + "건이던 달이 0건 — 원천 이상으로 보고 바꾸지 않음");
        }
    }

    // ---- 1 공고 ---------------------------------------------------------------

    @Bean
    @StepScope
    ItemReader<MonthTarget> presaleNoticeReader(PresaleRepository presales, AppProperties props,
                                                @Value("#{jobParameters['from']}") String from) {
        return new ListItemReader<>(months(presales, Dataset.APT_NOTICE, fromYm(props, from)));
    }

    @Bean
    @StepScope
    ItemProcessor<MonthTarget, NoticeMonth> presaleNoticeProcessor(ApplyhomeClient client, SkipRecorder skips,
                                                                   @Value("#{stepExecution}") StepExecution se,
                                                                   @Value("#{jobParameters['maxCalls']}") Long maxCalls) {
        QuotaGuard guard = new QuotaGuard(se, skips, remaining(se, maxCalls));
        return t -> guard.call(t.dataset() + ":" + t.ym(), () -> {
            YearMonth m = PresaleModels.parseYm(t.ym());
            var got = client.notices(m.atDay(1), m.plusMonths(1).atDay(1));
            guardEmpty(t, got.size());
            return new NoticeMonth(t.ym(), got);
        });
    }

    @Bean
    @StepScope
    ItemWriter<NoticeMonth> presaleNoticeWriter(PresaleRepository presales,
                                                @Value("#{jobExecutionContext['" + BatchKeys.COLLECT_RUN_ID + "']}") String runId) {
        UUID run = UUID.fromString(runId);
        return chunk -> presales.replaceNotices(List.copyOf(chunk.getItems()), run);
    }

    @Bean
    Step presaleNoticeStep(JobRepository repo, PlatformTransactionManager tm, ItemReader<MonthTarget> presaleNoticeReader,
                           ItemProcessor<MonthTarget, NoticeMonth> presaleNoticeProcessor, ItemWriter<NoticeMonth> presaleNoticeWriter,
                           AppProperties props) {
        var step = new StepBuilder("presaleNoticeStep", repo).<MonthTarget, NoticeMonth>chunk(5).transactionManager(tm)
                .reader(presaleNoticeReader).processor(presaleNoticeProcessor).writer(presaleNoticeWriter).allowStartIfComplete(true);
        AsyncTaskExecutor executor = MarketJobsConfig.apiExecutor("applyhome-", props.batch().apiConcurrency());
        return (executor == null ? step : step.taskExecutor(executor)).build();
    }

    // ---- 2 잔여세대 -------------------------------------------------------------

    @Bean
    @StepScope
    ItemReader<MonthTarget> presaleRemainderReader(PresaleRepository presales, AppProperties props,
                                                   @Value("#{jobParameters['from']}") String from) {
        return new ListItemReader<>(months(presales, Dataset.REMAINDER, fromYm(props, from)));
    }

    @Bean
    @StepScope
    ItemProcessor<MonthTarget, RemainderMonth> presaleRemainderProcessor(ApplyhomeClient client, SkipRecorder skips,
                                                                         @Value("#{stepExecution}") StepExecution se,
                                                                         @Value("#{jobParameters['maxCalls']}") Long maxCalls) {
        QuotaGuard guard = new QuotaGuard(se, skips, remaining(se, maxCalls));
        AtomicBoolean warned = new AtomicBoolean();
        return t -> guard.call(t.dataset() + ":" + t.ym(), () -> {
            YearMonth m = PresaleModels.parseYm(t.ym());
            List<Remainder> got;
            try {
                got = client.remainders(m.atDay(1), m.plusMonths(1).atDay(1));
            } catch (ApplyhomeDatasetEmptyException e) {
                // 잔여세대는 화면 표시와 원 공고 연결에만 씀 — 집계 · 지표 · R-X02 · 주택형의 입력이 아니라 그 달만 건너뛰고 뒤 단계는 계속(224).
                // null 이면 writer 에 가지 않아 원장 · 사라짐 표시가 없음(216) → 다음 실행의 pendingMonths 가 다시 부름. 공고 · 주택형의 빈 응답,
                // 429 · 자체 상한 · maxCalls 는 그대로 STOPPED. 남은 달도 하나씩 부름 — 읽은 항목 수 = 부른 수라 remaining() 이 맞음
                guard.skip(t.dataset() + ":" + t.ym(), "EMPTY_DATASET", e.getMessage());
                if (warned.compareAndSet(false, true)) log.warn("청약홈 잔여세대 데이터셋이 비어(totalCount 0) 그 달은 건너뜀 — 다음 실행에서 다시 받음: {}", t.ym());
                return null;
            }
            guardEmpty(t, got.size());
            return new RemainderMonth(t.ym(), got);
        });
    }

    @Bean
    @StepScope
    ItemWriter<RemainderMonth> presaleRemainderWriter(PresaleRepository presales,
                                                      @Value("#{jobExecutionContext['" + BatchKeys.COLLECT_RUN_ID + "']}") String runId) {
        UUID run = UUID.fromString(runId);
        return chunk -> presales.replaceRemainders(List.copyOf(chunk.getItems()), run);
    }

    @Bean
    Step presaleRemainderStep(JobRepository repo, PlatformTransactionManager tm, ItemReader<MonthTarget> presaleRemainderReader,
                              ItemProcessor<MonthTarget, RemainderMonth> presaleRemainderProcessor,
                              ItemWriter<RemainderMonth> presaleRemainderWriter, AppProperties props) {
        var step = new StepBuilder("presaleRemainderStep", repo).<MonthTarget, RemainderMonth>chunk(5).transactionManager(tm)
                .reader(presaleRemainderReader).processor(presaleRemainderProcessor).writer(presaleRemainderWriter)
                .allowStartIfComplete(true);
        AsyncTaskExecutor executor = MarketJobsConfig.apiExecutor("applyhome-r-", props.batch().apiConcurrency());
        return (executor == null ? step : step.taskExecutor(executor)).build();
    }

    // ---- 3 연결 · 4 집계 ---------------------------------------------------------

    @Bean
    Step presaleLinkStep(JobRepository repo, PlatformTransactionManager tm, PresaleRepository presales, RegionRepository regions,
                         SeedCatalog seed) {
        return new StepBuilder("presaleLinkStep", repo).tasklet((contribution, ctx) -> {
            var regionMatcher = new AddressRegionMatcher(regions.refs(), seed.regionAliases());
            var builderMatcher = new BuilderNameMatcher(presales.companies(), seed.builderAliases());
            if (!builderMatcher.unknownAliasCorps().isEmpty()) {
                log.warn("builder_aliases.yml 의 corp_code {} 가 기업 목록에 없어 그 별칭은 쓰지 않음 (corpCodeSyncJob 전이거나 오기)",
                        builderMatcher.unknownAliasCorps());
            }
            var counts = presales.relinkAll(regionMatcher, builderMatcher);
            contribution.incrementWriteCount(counts.regionMatch().values().stream().mapToInt(Integer::intValue).sum());
            log.info("청약홈 연결 — 공고 지역 {} · 시공사 {} · 잔여세대 원 공고 연결 {}/{}", counts.regionMatch(), counts.builderMatch(),
                    counts.remainderLinked(), counts.remainders());
            return RepeatStatus.FINISHED;
        }, tm).allowStartIfComplete(true).build();
    }

    @Bean
    Step presaleAggregateStep(JobRepository repo, PlatformTransactionManager tm, PresaleRepository presales) {
        return new StepBuilder("presaleAggregateStep", repo).tasklet((contribution, ctx) -> {
            UUID run = UUID.fromString(contribution.getStepExecution().getJobExecution().getExecutionContext()
                    .getString(BatchKeys.COLLECT_RUN_ID));
            var n = presales.aggregate(PresaleModels.ym(YearMonth.now(Kst.ZONE)), run);
            contribution.incrementWriteCount(n.values().stream().mapToInt(Integer::intValue).sum());
            log.info("청약홈 월 집계 {}", n);
            return RepeatStatus.FINISHED;
        }, tm).allowStartIfComplete(true).build();
    }

    // ---- 5 주택형 --------------------------------------------------------------

    @Bean
    @StepScope
    ItemReader<ModelTarget> presaleModelReader(PresaleRepository presales) {
        List<ModelTarget> plan = presales.pendingModels();
        log.info("청약홈 주택형 수집 대상 공고 {}건", plan.size());
        return new ListItemReader<>(plan);
    }

    @Bean
    @StepScope
    ItemProcessor<ModelTarget, ModelSet> presaleModelProcessor(ApplyhomeClient client, SkipRecorder skips,
                                                               @Value("#{stepExecution}") StepExecution se,
                                                               @Value("#{jobParameters['maxCalls']}") Long maxCalls) {
        QuotaGuard guard = new QuotaGuard(se, skips, remaining(se, maxCalls));
        return t -> guard.call("MODEL:" + t.houseManageNo() + "/" + t.pblancNo(),
                () -> new ModelSet(t, client.models(t.houseManageNo(), t.pblancNo())));
    }

    @Bean
    @StepScope
    ItemWriter<ModelSet> presaleModelWriter(PresaleRepository presales,
                                            @Value("#{jobExecutionContext['" + BatchKeys.COLLECT_RUN_ID + "']}") String runId) {
        UUID run = UUID.fromString(runId);
        return chunk -> presales.replaceModels(List.copyOf(chunk.getItems()), run);
    }

    @Bean
    Step presaleModelStep(JobRepository repo, PlatformTransactionManager tm, ItemReader<ModelTarget> presaleModelReader,
                          ItemProcessor<ModelTarget, ModelSet> presaleModelProcessor, ItemWriter<ModelSet> presaleModelWriter,
                          AppProperties props) {
        var step = new StepBuilder("presaleModelStep", repo).<ModelTarget, ModelSet>chunk(10).transactionManager(tm)
                .reader(presaleModelReader).processor(presaleModelProcessor).writer(presaleModelWriter).allowStartIfComplete(true);
        AsyncTaskExecutor executor = MarketJobsConfig.apiExecutor("applyhome-m-", props.batch().apiConcurrency());
        return (executor == null ? step : step.taskExecutor(executor)).build();
    }
}
