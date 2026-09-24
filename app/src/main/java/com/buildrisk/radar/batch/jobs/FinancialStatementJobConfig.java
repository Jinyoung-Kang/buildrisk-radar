package com.buildrisk.radar.batch.jobs;

import com.buildrisk.radar.adapters.dart.DartClient;
import com.buildrisk.radar.adapters.dart.DartModels.FsResponse;
import com.buildrisk.radar.adapters.dart.DartModels.FsRow;
import com.buildrisk.radar.adapters.common.ApiQuotaService;
import com.buildrisk.radar.batch.support.BatchKeys;
import com.buildrisk.radar.batch.support.QuotaGuard;
import com.buildrisk.radar.batch.support.RunRecorder;
import com.buildrisk.radar.batch.support.SkipRecorder;
import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.domain.account.FsRepository;
import com.buildrisk.radar.domain.account.FsRepository.FsTarget;
import com.buildrisk.radar.domain.account.PeriodKeys;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * financialStatementJob (FR-201~203, 그림 1)
 *   Reader   : 대상 (기업 × 연도 × 보고서) 중 미수집 조합 — 최근 기간부터
 *   Processor: fnlttSinglAcntAll(CFS) → 013 이면 OFS → 둘 다 013 이면 NO_DATA(스킵 기록)
 *              020·일일 상한·maxCalls → STOPPED (다음 실행에서 restart 로 이어감)
 *   Writer   : dart.fs_raw INSERT(불변) + dart.fs_fetch 상태 (chunk 20, 한 트랜잭션)
 *
 * Job 파라미터: fromYear, toYear, reprtCodes(쉼표), maxCalls(선택), runAt(식별용)
 */
@Configuration
public class FinancialStatementJobConfig {

    public record FetchResult(FsTarget target, String status, String fsDiv, List<FsRow> rows, String rceptNo) {}

    @Bean
    Job financialStatementJob(JobRepository repo, Step financialStatementStep, RunRecorder runs) {
        return new JobBuilder("financialStatementJob", repo).listener(runs.collect("DART"))
                .start(financialStatementStep).build();
    }

    @Bean
    Step financialStatementStep(JobRepository repo, PlatformTransactionManager tm, FsTargetReader fsTargetReader,
                                FsFetchProcessor fsFetchProcessor, FsRepository fs, SkipRecorder skips) {
        return new StepBuilder("financialStatementStep", repo).<FsTarget, FetchResult>chunk(20).transactionManager(tm)
                .reader(fsTargetReader).processor(fsFetchProcessor)
                .writer(chunk -> {
                    for (FetchResult r : chunk.getItems()) {
                        Long exec = fsFetchProcessor.jobExecutionId();
                        if ("OK".equals(r.status())) {
                            fs.insertRaw(r.target(), r.fsDiv(), r.rows(), exec);
                            fs.upsertFetch(r.target(), "OK", r.fsDiv(), r.rows().size(), r.rceptNo(), null, exec);
                        } else {
                            fs.upsertFetch(r.target(), "NO_DATA", null, 0, null, "CFS·OFS 모두 013", exec);
                            skips.record(exec, "financialStatementJob", "financialStatementStep", r.target().key(),
                                    "NO_DATA", "CFS·OFS 모두 013 (조회된 데이터 없음)");
                        }
                    }
                })
                .build();
    }

    @Bean
    @StepScope
    FsTargetReader fsTargetReader(FsRepository fs, AppProperties props,
                                  @Value("#{jobParameters['fromYear']}") Long fromYear,
                                  @Value("#{jobParameters['toYear']}") Long toYear,
                                  @Value("#{jobParameters['reprtCodes']}") String reprtCodes) {
        int from = fromYear == null ? props.dart().fromYear() : fromYear.intValue();
        int to = toYear == null ? ApiQuotaService.today().getYear() : toYear.intValue();
        List<String> reprts = reprtCodes == null || reprtCodes.isBlank() ? PeriodKeys.REPRT_CODES
                : Arrays.stream(reprtCodes.split(",")).map(String::trim).toList();
        return new FsTargetReader(fs, from, to, reprts);
    }

    /** 열 때마다 DB 에서 미수집 조합을 다시 계산 — 재시작 시 커밋된 청크는 자연히 빠집니다 (중복·누락 0) */
    public static class FsTargetReader implements ItemStreamReader<FsTarget> {
        private final FsRepository fs;
        private final int from, to;
        private final List<String> reprts;
        private Iterator<FsTarget> it;

        FsTargetReader(FsRepository fs, int from, int to, List<String> reprts) {
            this.fs = fs;
            this.from = from;
            this.to = to;
            this.reprts = reprts;
        }

        @Override
        public void open(ExecutionContext ctx) {
            List<FsTarget> targets = fs.pendingTargets(from, to, reprts, ApiQuotaService.today());
            ctx.putInt(BatchKeys.PLANNED_CALLS, targets.size());
            it = targets.iterator();
        }

        @Override
        public FsTarget read() { return it != null && it.hasNext() ? it.next() : null; }
    }

    @Bean
    @StepScope
    FsFetchProcessor fsFetchProcessor(DartClient dart, SkipRecorder skips, @Value("#{stepExecution}") StepExecution se,
                                      @Value("#{jobParameters['maxCalls']}") Long maxCalls) {
        return new FsFetchProcessor(dart, se, new QuotaGuard(se, skips, maxCalls == null ? Long.MAX_VALUE : maxCalls));
    }

    public static class FsFetchProcessor implements ItemProcessor<FsTarget, FetchResult> {
        private final DartClient dart;
        private final StepExecution se;
        private final QuotaGuard guard;

        FsFetchProcessor(DartClient dart, StepExecution se, QuotaGuard guard) {
            this.dart = dart;
            this.se = se;
            this.guard = guard;
        }

        Long jobExecutionId() { return se.getJobExecutionId(); }

        @Override
        public FetchResult process(FsTarget t) {
            return guard.call(t.key(), () -> {
                for (String fsDiv : List.of("CFS", "OFS")) {
                    FsResponse r = dart.financialStatements(t.corpCode(), t.bsnsYear(), t.reprtCode(), fsDiv);
                    if (!r.noData()) {
                        return new FetchResult(t, "OK", fsDiv, r.rows(), r.rows().get(0).rceptNo());
                    }
                }
                return new FetchResult(t, "NO_DATA", null, List.of(), null);
            });
        }
    }
}
