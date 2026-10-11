package com.buildrisk.radar.batch.jobs;

import com.buildrisk.radar.adapters.dart.DartClient;
import com.buildrisk.radar.adapters.seed.SeedCatalog;
import com.buildrisk.radar.batch.support.QuotaGuard;
import com.buildrisk.radar.batch.support.RunRecorder;
import com.buildrisk.radar.batch.support.SkipRecorder;
import com.buildrisk.radar.company.data.CompanyRepository;
import com.buildrisk.radar.company.domain.CompanyModels.CompanyProfile;
import com.buildrisk.radar.company.domain.CompanyModels.CorpCode;
import com.buildrisk.radar.company.domain.UniversePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.support.PostgresPagingQueryProvider;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;

/**
 * EPIC-1 기업 유니버스
 *   corpCodeSyncJob   : 고유번호 Zip → CORPCODE.xml 스트리밍 → ref.company UPSERT (chunk 1000)
 *   companyProfileJob : 상장사 기업개황 → 업종코드 (chunk 50) → 유니버스 판정 + 변경 이력
 */
@Configuration
public class UniverseJobsConfig {
    private static final Logger log = LoggerFactory.getLogger(UniverseJobsConfig.class);

    // ---------------------------------------------------------------- corpCodeSyncJob

    @Bean
    Job corpCodeSyncJob(JobRepository repo, Step corpCodeDownloadStep, Step corpCodeLoadStep, RunRecorder runs) {
        return new JobBuilder("corpCodeSyncJob", repo).listener(runs.collect("DART")).listener(removeCorpCodeFileWhenDone())
                .start(corpCodeDownloadStep).next(corpCodeLoadStep).build();
    }

    /**
     * 끝나면(COMPLETED) 임시 CORPCODE.xml 과 디렉터리를 지움 — /tmp 는 메모리(tmpfs)라 매주 쌓이면 worker 메모리 한도를 먹음 (VERIFICATION 137).
     * 실패 · 멈춤이면 restart 가 같은 파일을 이어 읽으므로 남김
     */
    private static JobExecutionListener removeCorpCodeFileWhenDone() {
        return new JobExecutionListener() {
            @Override
            public void afterJob(JobExecution je) {
                if (je.getStatus() != BatchStatus.COMPLETED || !je.getExecutionContext().containsKey("corpCodeXml")) return;
                Path xml = Path.of(je.getExecutionContext().getString("corpCodeXml"));
                try {
                    Files.deleteIfExists(xml);
                    Files.deleteIfExists(xml.getParent());
                } catch (IOException e) {
                    log.warn("CORPCODE 임시 파일을 지우지 못함 {}: {}", xml, e.getMessage());
                }
            }
        };
    }

    /**
     * restart 때도 돎(allowStartIfComplete) — 받아 둔 파일이 있으면 그대로 쓰고, 없으면(컨테이너가 바뀌어 tmpfs 가 비었음) 다시 받음.
     * 완료된 Step 이라 건너뛰면 적재가 사라진 파일을 열다 매번 실패해 고유번호 동기화가 멈췄음 (VERIFICATION 183)
     */
    @Bean
    Step corpCodeDownloadStep(JobRepository repo, PlatformTransactionManager tm, DartClient dart) {
        return new StepBuilder("corpCodeDownloadStep", repo).tasklet((contribution, chunk) -> {
            var je = chunk.getStepContext().getStepExecution().getJobExecution();
            var ctx = je.getExecutionContext();
            if (ctx.containsKey("corpCodeXml") && Files.isRegularFile(Path.of(ctx.getString("corpCodeXml")))) {
                log.info("CORPCODE.xml 재사용: {}", ctx.getString("corpCodeXml"));
                return RepeatStatus.FINISHED;
            }
            // 소유자 전용 권한(rwx------)의 새 임시 디렉터리 — 예측 가능한 공유 경로를 쓰지 않음. 경로는 실행 컨텍스트에 남아 restart 때 재사용
            Path dir = Files.createTempDirectory("buildrisk-corp-" + je.getJobInstance().getInstanceId() + "-");
            Path xml = dart.downloadCorpCodeXml(dir);
            ctx.putString("corpCodeXml", xml.toString());
            log.info("CORPCODE.xml 저장: {}", xml);
            return RepeatStatus.FINISHED;
        }, tm).allowStartIfComplete(true).build();
    }

    /** 읽은 위치를 저장하지 않음 — restart 때 다시 받은 파일은 이전 파일과 순서 · 건수가 다를 수 있음. 적재는 멱등 upsert 라 처음부터(약 5초) */
    @Bean
    @StepScope
    CorpCodeXmlReader corpCodeXmlReader(@Value("#{jobExecutionContext['corpCodeXml']}") String path) {
        CorpCodeXmlReader reader = new CorpCodeXmlReader(Path.of(path));
        reader.setSaveState(false);
        return reader;
    }

    @Bean
    Step corpCodeLoadStep(JobRepository repo, PlatformTransactionManager tm, CorpCodeXmlReader corpCodeXmlReader,
                          CompanyRepository companies, SkipRecorder skips) {
        return new StepBuilder("corpCodeLoadStep", repo).<CorpCode, CorpCode>chunk(1000).transactionManager(tm)
                .reader(corpCodeXmlReader)
                .processor(c -> {
                    if (c.corpCode() == null || c.corpCode().length() != 8 || c.corpName() == null) {
                        throw new IllegalArgumentException("corp_code/corp_name 형식 오류: " + c);
                    }
                    return c;
                })
                .writer(chunk -> {
                    // write 건수는 읽은 전체, 실제로 바뀐 행(modify_date 변경분)은 따로 셈 (FR-101)
                    int changed = companies.upsertCorpCodes(chunk.getItems());
                    var ctx = StepSynchronizationManager.getContext()
                            .getStepExecution().getExecutionContext();
                    ctx.putLong("changedRows", ctx.getLong("changedRows", 0L) + changed);
                })
                .faultTolerant().skip(IllegalArgumentException.class).skipLimit(100)
                .skipListener(new SkipListener<CorpCode, CorpCode>() {
                    @Override
                    public void onSkipInProcess(CorpCode item, Throwable t) {
                        skips.record(null, "corpCodeSyncJob", "corpCodeLoadStep", String.valueOf(item.corpCode()),
                                "PARSE_ERROR", t.getMessage());
                    }
                })
                .build();
    }

    // ---------------------------------------------------------------- companyProfileJob

    @Bean
    Job companyProfileJob(JobRepository repo, Step companyProfileStep, Step universeStep, RunRecorder runs) {
        return new JobBuilder("companyProfileJob", repo).listener(runs.collect("DART"))
                .start(companyProfileStep).next(universeStep).build();
    }

    /**
     * 기업개황이 없거나 30일 지났거나 고유번호 정보가 바뀐 상장사 — corp_code 키셋 페이징.
     * WHERE 가 처리한 행을 빼므로 재시작 지점은 DB 상태에서 나옵니다(ADR-008). 오프셋(페이지 안 건수)을 저장하면
     * 재시작 때 아직 처리하지 않은 행을 그만큼 건너뛰므로 saveState=false (실제로 겪은 누락 — CompanyProfileRestartIT).
     */
    @Bean
    @StepScope
    JdbcPagingItemReader<String> profileTargetReader(DataSource ds) throws Exception {
        PostgresPagingQueryProvider q = new PostgresPagingQueryProvider();
        q.setSelectClause("corp_code");
        q.setFromClause("ref.company");
        q.setWhereClause("stock_code IS NOT NULL AND (profile_fetched_at IS NULL OR profile_fetched_at < updated_at "
                + "OR profile_fetched_at < now() - interval '30 days')");
        q.setSortKeys(Map.of("corp_code", Order.ASCENDING));
        JdbcPagingItemReader<String> r = new JdbcPagingItemReader<>(ds, q);
        r.setRowMapper((rs, i) -> rs.getString(1));
        r.setPageSize(200);
        r.setName("profileTargetReader");
        r.setSaveState(false);
        return r;
    }

    @Bean
    @StepScope
    ProfileProcessor profileProcessor(DartClient dart, CompanyRepository companies, SkipRecorder skips,
                                      @Value("#{stepExecution}") StepExecution se) {
        return new ProfileProcessor(dart, companies, skips, se);
    }

    @Bean
    Step companyProfileStep(JobRepository repo, PlatformTransactionManager tm, JdbcPagingItemReader<String> profileTargetReader,
                            ProfileProcessor profileProcessor, CompanyRepository companies) {
        return new StepBuilder("companyProfileStep", repo).<String, CompanyProfile>chunk(50).transactionManager(tm)
                .reader(profileTargetReader).processor(profileProcessor)
                .writer(chunk -> companies.updateProfiles(chunk.getItems()))
                .build();
    }

    /** DART 기업개황 호출. 013 → 스킵 기록, 020·일일 상한 → Step 을 STOPPED 로 */
    public static class ProfileProcessor implements ItemProcessor<String, CompanyProfile> {
        private final DartClient dart;
        private final CompanyRepository companies;
        private final SkipRecorder skips;
        private final QuotaGuard guard;

        ProfileProcessor(DartClient dart, CompanyRepository companies, SkipRecorder skips, StepExecution se) {
            this.dart = dart;
            this.companies = companies;
            this.skips = skips;
            this.guard = new QuotaGuard(se, skips, Long.MAX_VALUE);
        }

        @Override
        public CompanyProfile process(String corpCode) {
            return guard.call(corpCode, () -> {
                CompanyProfile p = dart.company(corpCode);
                if (p == null) {
                    companies.markProfileFetched(corpCode);
                    guard.skip(corpCode, "NO_DATA", "기업개황 013");
                }
                return p;
            });
        }
    }

    @Bean
    Step universeStep(JobRepository repo, PlatformTransactionManager tm, CompanyRepository companies, SeedCatalog seed) {
        return new StepBuilder("universeStep", repo).tasklet((contribution, chunk) -> {
            Long exec = chunk.getStepContext().getStepExecution().getJobExecutionId();
            UniversePolicy policy = new UniversePolicy(seed.industryPrefixes(), seed.universeOverrides());
            int in = 0, out = 0;
            for (var r : companies.universeCandidates()) {
                var d = policy.decide(r.corpCode(), r.stockCode(), r.corpCls(), r.indutyCode());
                if (d.target() != r.isTarget()) {
                    companies.setTarget(r.corpCode(), d.target(), d.reason(), exec);
                    if (d.target()) in++;
                    else out++;
                } else if (d.target() && !d.reason().equals(r.reason())) {
                    companies.updateReason(r.corpCode(), d.reason());
                }
            }
            contribution.incrementWriteCount(in + out);
            log.info("유니버스 판정: 편입 {} · 제외 {} · 현재 대상 {}", in, out, companies.targetCorpCodes().size());
            return RepeatStatus.FINISHED;
        }, tm).build();
    }
}
