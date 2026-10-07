package com.buildrisk.radar.batch.jobs;

import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.adapters.common.ApiQuotaService;
import com.buildrisk.radar.adapters.dart.DartClient;
import com.buildrisk.radar.adapters.dart.DartModels.Disclosure;
import com.buildrisk.radar.adapters.dart.DartModels.DisclosurePage;
import com.buildrisk.radar.batch.support.QuotaGuard;
import com.buildrisk.radar.batch.support.RunRecorder;
import com.buildrisk.radar.batch.support.SkipRecorder;
import com.buildrisk.radar.common.seed.SeedCatalog;
import com.buildrisk.radar.domain.disclosure.DisclosureRepository;
import com.buildrisk.radar.domain.disclosure.DisclosureRepository.Classified;
import com.buildrisk.radar.domain.disclosure.EventClassifier;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
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

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * disclosureSyncJob (FR-301~302)
 *   step1: 유니버스 기업 × 최근 N일(days, 기본 7) list.json → 키워드 사전 분류 → UPSERT (chunk 20 기업)
 *          처음 수집하는 기업은 재무제표와 같은 기간(FS_FROM_YEAR 1월 1일)부터 — 재무 악화와 공시 사건을 같은 기간에서 비교하기 위해
 *          (예전에는 1년만 받아 2021~2024 의 회생 · 워크아웃 사건이 빠졌음). from 파라미터로 기존 기업도 백필
 *          (corp_code 를 주면 list.json 의 3개월 기간 제한이 없음 — OpenDART 공시검색 가이드)
 *   step2: 저장된 전체 공시를 현재 사전으로 다시 분류 (사전을 고쳐도 과거 공시에 반영)
 */
@Configuration
public class DisclosureSyncJobConfig {

    @Bean
    Job disclosureSyncJob(JobRepository repo, Step disclosureFetchStep, Step disclosureReclassifyStep, RunRecorder runs) {
        return new JobBuilder("disclosureSyncJob", repo).listener(runs.collect("DART"))
                .start(disclosureFetchStep).next(disclosureReclassifyStep).build();
    }

    @Bean
    @StepScope
    JdbcPagingItemReader<String> disclosureTargetReader(DataSource ds) {
        PostgresPagingQueryProvider q = new PostgresPagingQueryProvider();
        q.setSelectClause("corp_code");
        q.setFromClause("ref.company");
        q.setWhereClause("is_target");
        q.setSortKeys(Map.of("corp_code", Order.ASCENDING));
        JdbcPagingItemReader<String> r = new JdbcPagingItemReader<>(ds, q);
        r.setRowMapper((rs, i) -> rs.getString(1));
        r.setPageSize(100);
        r.setName("disclosureTargetReader");
        return r;
    }

    @Bean
    @StepScope
    ItemProcessor<String, List<Classified>> disclosureProcessor(DartClient dart, SeedCatalog seed,
            DisclosureRepository repo, SkipRecorder skips, @Value("#{stepExecution}") StepExecution se,
            @Value("#{jobParameters['days']}") Long days, @Value("#{jobParameters['from']}") String fromParam,
            AppProperties props) {
        EventClassifier classifier = seed.eventClassifier();
        QuotaGuard guard = new QuotaGuard(se, skips, Long.MAX_VALUE);
        LocalDate today = ApiQuotaService.today();
        LocalDate backfill = fromParam == null ? null : LocalDate.parse(fromParam);
        LocalDate firstFetch = LocalDate.of(props.dart().fromYear(), 1, 1);
        return corp -> guard.call(corp, () -> {
            LocalDate latest = repo.latestDate(corp);
            // 처음 수집하는 기업은 재무제표 기간 시작부터, 이후는 최근 days(기본 7)일. 오래 안 돌렸으면 마지막 공시일부터 (누락 방지)
            LocalDate window = today.minusDays(days == null ? 7 : days);
            LocalDate from = backfill != null ? backfill
                    : latest == null ? firstFetch : latest.isBefore(window) ? latest : window;
            List<Classified> out = new ArrayList<>();
            int page = 1;
            DisclosurePage p;
            do {
                p = dart.disclosures(corp, from, today, page);
                for (Disclosure d : p.items()) {
                    var c = classifier.classify(d.reportNm());
                    out.add(new Classified(d, c.eventType(), c.keyword()));
                }
                page++;
            } while (page <= p.totalPage());
            return out;
        });
    }

    @Bean
    Step disclosureFetchStep(JobRepository repo, PlatformTransactionManager tm, JdbcPagingItemReader<String> disclosureTargetReader,
                             ItemProcessor<String, List<Classified>> disclosureProcessor, DisclosureRepository disclosures) {
        return new StepBuilder("disclosureFetchStep", repo).<String, List<Classified>>chunk(20).transactionManager(tm)
                .reader(disclosureTargetReader).processor(disclosureProcessor)
                .writer(chunk -> {
                    List<Classified> all = new ArrayList<>();
                    chunk.getItems().forEach(all::addAll);
                    disclosures.upsert(all, null);
                })
                .build();
    }

    @Bean
    Step disclosureReclassifyStep(JobRepository repo, PlatformTransactionManager tm, SeedCatalog seed,
                                  DisclosureRepository disclosures) {
        return new StepBuilder("disclosureReclassifyStep", repo).tasklet((contribution, chunk) -> {
            EventClassifier classifier = seed.eventClassifier();
            int changed = 0;
            for (var d : disclosures.all()) {
                var c = classifier.classify(d.reportNm());
                if (!c.eventType().equals(d.eventType())) {
                    disclosures.reclassify(d.rceptNo(), c.eventType(), c.keyword());
                    changed++;
                }
            }
            contribution.incrementWriteCount(changed);
            return RepeatStatus.FINISHED;
        }, tm).build();
    }
}
