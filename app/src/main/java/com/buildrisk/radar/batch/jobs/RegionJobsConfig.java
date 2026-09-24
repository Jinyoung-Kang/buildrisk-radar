package com.buildrisk.radar.batch.jobs;

import com.buildrisk.radar.adapters.common.ApiQuotaService;
import com.buildrisk.radar.adapters.kosis.KosisClient;
import com.buildrisk.radar.adapters.rone.RoneClient;
import com.buildrisk.radar.adapters.sgis.SgisClient;
import com.buildrisk.radar.adapters.vworld.VworldClient;
import com.buildrisk.radar.batch.support.BatchKeys;
import com.buildrisk.radar.batch.support.RunRecorder;
import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.common.seed.SeedCatalog;
import com.buildrisk.radar.domain.region.RegionAliases;
import com.buildrisk.radar.domain.region.RegionRepository;
import com.buildrisk.radar.domain.region.RegionRepository.BoundaryRow;
import com.buildrisk.radar.domain.region.RegionRepository.SeriesDef;
import com.buildrisk.radar.domain.region.RegionRepository.StatRow;
import com.buildrisk.radar.domain.region.RegionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * EPIC-4 지역 시장
 *   boundaryLoadJob  : V-World 시군구 경계 → ref.region (+ 일반구 → 시 합성, 커버리지 단순화) — FR-404
 *   sgisHouseholdJob : SGIS 총조사 주요지표 총가구 → region_stat (연) — FR-403
 *   kosisUnsoldJob   : KOSIS 시·군·구별 미분양현황 최근 36개월 → region_stat (월) — FR-401
 *   roneIndexJob     : R-ONE 아파트 매매·전세 가격지수 최근 36개월 → region_stat (월) — FR-402
 * 출처 지역 이름은 RegionResolver 가 기준 시군구로 매핑하고 결과를 region_code_map 에 남깁니다 (FR-405).
 */
@Configuration
public class RegionJobsConfig {
    private static final Logger log = LoggerFactory.getLogger(RegionJobsConfig.class);
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    // ---------------------------------------------------------------- boundaryLoadJob

    @Bean
    Job boundaryLoadJob(JobRepository repo, Step boundaryFetchStep, Step boundaryDeriveStep, RunRecorder runs) {
        return new JobBuilder("boundaryLoadJob", repo).listener(runs.collect("BOUNDARY"))
                .start(boundaryFetchStep).next(boundaryDeriveStep).build();
    }

    /**
     * 경계 출처: 기본은 V-World(법정 시군구 코드). V-World 키가 없거나 source=SGIS 면 SGIS 행정구역 경계(UTM-K → 4326)로
     * 대신합니다 (U-3 대응 — 설계 단계에서 V-World 공식 문서를 확인하지 못한 위험). 지역 매핑은 이름 기반이라 두 경로 모두 동작합니다.
     */
    @Bean
    @StepScope
    ItemReader<BoundaryRow> boundaryReader(VworldClient vworld, SgisClient sgis, SeedCatalog seed, RegionRepository regions,
                                           @Value("#{jobParameters['source']}") String sourceParam,
                                           @Value("#{jobParameters['year']}") Long yearParam) {
        String source = sourceParam != null ? sourceParam : vworld.configured() ? "VWORLD" : "SGIS";
        regions.purgeIfSourceChanged(source);
        if ("SGIS".equals(source)) {
            int year = yearParam == null ? 2025 : yearParam.intValue();
            Map<String, String> sidoCodes = seed.regionAliases().sgisSido();
            return lazyList(() -> {
                List<BoundaryRow> out = new ArrayList<>();
                for (String sido : sidoCodes.keySet()) {
                    for (SgisClient.Boundary b : sgis.boundary(year, sido)) {
                        if (b.admCd() == null || b.admCd().length() != 5 || b.admNm() == null) continue;
                        int sp = b.admNm().indexOf(' ');
                        String sidoName = sp < 0 ? b.admNm() : b.admNm().substring(0, sp);
                        String name = sp < 0 ? b.admNm() : b.admNm().substring(sp + 1);
                        out.add(new BoundaryRow(b.admCd(), name, b.admNm(), sidoName, name.contains(" ") ? 3 : 2,
                                b.geometryJson(), 5179, "SGIS"));
                    }
                    log.info("SGIS 경계 {} 시도 코드 {} 누적 {}건", year, sido, out.size());
                }
                return out;
            });
        }
        return new ItemReader<>() {
            private final Deque<BoundaryRow> buf = new ArrayDeque<>();
            private int page = 0;
            private int totalPages = Integer.MAX_VALUE;

            @Override
            public BoundaryRow read() {
                while (buf.isEmpty() && page < totalPages) {
                    var p = vworld.page(++page, 100);
                    totalPages = p.totalPages();
                    for (var f : p.features()) {
                        if (f.sigCd() == null || f.sigCd().length() != 5 || f.korName() == null) continue;
                        String sidoName = f.fullName() == null ? "" : f.fullName().split(" ")[0];
                        buf.add(new BoundaryRow(f.sigCd(), f.korName(), f.fullName(), sidoName,
                                f.korName().contains(" ") ? 3 : 2, f.geometryJson(), 4326, "VWORLD"));
                    }
                    log.info("V-World 경계 {}/{} 페이지 ({}건)", page, totalPages, p.features().size());
                }
                return buf.poll();
            }
        };
    }

    @Bean
    Step boundaryFetchStep(JobRepository repo, PlatformTransactionManager tm, ItemReader<BoundaryRow> boundaryReader,
                           RegionRepository regions) {
        return new StepBuilder("boundaryFetchStep", repo).<BoundaryRow, BoundaryRow>chunk(50).transactionManager(tm)
                .reader(boundaryReader)
                .writer(chunk -> regions.upsertBoundaries(chunk.getItems()))
                .build();
    }

    @Bean
    Step boundaryDeriveStep(JobRepository repo, PlatformTransactionManager tm, RegionRepository regions) {
        return new StepBuilder("boundaryDeriveStep", repo).tasklet((contribution, chunk) -> {
            int parents = regions.synthesizeParentsAndSimplify();
            contribution.incrementWriteCount(parents);
            log.info("일반구 → 시 합성 {}곳, 전체 {}곳", parents, regions.count());
            return RepeatStatus.FINISHED;
        }, tm).build();
    }

    // ---------------------------------------------------------------- 공통: 통계 행 + 지역 해석

    /** 출처 한 행을 지역 해석 전 형태로 */
    public record SourceStat(String seriesId, String source, String sourceCode, String sido, List<String> tokens,
                             String period, java.math.BigDecimal value, String raw) {}

    @Bean
    @StepScope
    ItemProcessor<SourceStat, StatRow> regionStatProcessor(RegionRepository regions, SeedCatalog seed,
            @Value("#{jobExecutionContext['" + BatchKeys.COLLECT_RUN_ID + "']}") String collectRunId) {
        RegionAliases aliases = seed.regionAliases();
        RegionResolver resolver = new RegionResolver(regions, aliases);
        UUID run = UUID.fromString(collectRunId);
        return s -> resolver.resolve(s.source(), s.sourceCode(), s.sido(), s.tokens())
                .map(r -> new StatRow(s.seriesId(), r.regionCd(), s.period(), s.value(),
                        s.value() == null ? s.raw() : null, s.sourceCode(), run))
                .orElse(null);
    }

    private Step statStep(String name, JobRepository repo, PlatformTransactionManager tm, ItemReader<SourceStat> reader,
                          ItemProcessor<SourceStat, StatRow> processor, RegionRepository regions) {
        return new StepBuilder(name, repo).<SourceStat, StatRow>chunk(200).transactionManager(tm)
                .reader(reader).processor(processor).writer(chunk -> regions.upsertStats(chunk.getItems())).build();
    }

    private static <T> ItemReader<T> lazyList(java.util.function.Supplier<List<T>> loader) {
        return new ItemReader<>() {
            private java.util.Iterator<T> it;

            @Override
            public T read() {
                if (it == null) it = loader.get().iterator();
                return it.hasNext() ? it.next() : null;
            }
        };
    }

    private static Map<String, Object> params(ObjectMapper mapper, SeriesDef s) {
        JsonNode n = mapper.readTree(s.paramsJson());
        Map<String, Object> m = new LinkedHashMap<>();
        n.forEachEntry((k, v) -> m.put(k, v.isNumber() ? v.numberValue() : v.asString()));
        return m;
    }

    // ---------------------------------------------------------------- sgisHouseholdJob

    @Bean
    Job sgisHouseholdJob(JobRepository repo, Step sgisHouseholdStep, RunRecorder runs) {
        return new JobBuilder("sgisHouseholdJob", repo).listener(runs.collect("SGIS")).start(sgisHouseholdStep).build();
    }

    @Bean
    @StepScope
    ItemReader<SourceStat> sgisReader(SgisClient sgis, RegionRepository regions, SeedCatalog seed, ObjectMapper mapper,
                                      @Value("#{jobParameters['year']}") Long yearParam) {
        return lazyList(() -> {
            List<SourceStat> out = new ArrayList<>();
            Map<String, String> sidoCodes = seed.regionAliases().sgisSido();
            for (SeriesDef s : regions.series("SGIS")) {
                Object y = params(mapper, s).get("year");
                int year = yearParam != null ? yearParam.intValue() : y == null ? 2024 : Integer.parseInt(String.valueOf(y));
                for (var e : sidoCodes.entrySet()) {
                    for (SgisClient.Row r : sgis.population(year, e.getKey())) {
                        out.add(new SourceStat(s.seriesId(), "SGIS", r.admCd(), e.getValue(), List.of(r.admNm()),
                                String.valueOf(year), r.households(), null));
                    }
                }
            }
            return out;
        });
    }

    @Bean
    Step sgisHouseholdStep(JobRepository repo, PlatformTransactionManager tm, ItemReader<SourceStat> sgisReader,
                           ItemProcessor<SourceStat, StatRow> regionStatProcessor, RegionRepository regions) {
        return statStep("sgisHouseholdStep", repo, tm, sgisReader, regionStatProcessor, regions);
    }

    // ---------------------------------------------------------------- kosisUnsoldJob

    @Bean
    Job kosisUnsoldJob(JobRepository repo, Step kosisUnsoldStep, RunRecorder runs) {
        return new JobBuilder("kosisUnsoldJob", repo).listener(runs.collect("KOSIS")).start(kosisUnsoldStep).build();
    }

    @Bean
    @StepScope
    ItemReader<SourceStat> kosisReader(KosisClient kosis, RegionRepository regions, AppProperties props, ObjectMapper mapper) {
        return lazyList(() -> {
            List<SourceStat> out = new ArrayList<>();
            YearMonth now = YearMonth.now(ApiQuotaService.KST);
            String start = now.minusMonths(props.kosis().months()).format(YM);
            for (SeriesDef s : regions.series("KOSIS")) {
                Map<String, Object> p = params(mapper, s);
                String total = String.valueOf(p.getOrDefault("totalName", "계"));
                Map<String, String> objL = new LinkedHashMap<>();
                p.forEach((k, v) -> { if (k.startsWith("objL")) objL.put(k, String.valueOf(v)); });
                // KOSIS 는 한 번에 4만 셀(분류 조합 × 기간)까지만 돌려주므로(err 31) 6개월씩 나눠 받습니다
                for (YearMonth from = now.minusMonths(props.kosis().months()); !from.isAfter(now); from = from.plusMonths(6)) {
                    YearMonth to = from.plusMonths(5).isAfter(now) ? now : from.plusMonths(5);
                    for (KosisClient.Row r : kosis.data(String.valueOf(p.get("orgId")), s.table(), s.item(), objL, "M",
                            from.format(YM), to.format(YM))) {
                        if (r.classNames().size() < 2 || total.equals(r.classNames().get(r.classNames().size() - 1))) continue;
                        List<String> names = r.classNames();
                        out.add(new SourceStat(s.seriesId(), "KOSIS", String.join("/", r.classCodes()), names.get(0),
                                names.subList(1, names.size()), r.prdDe(), r.value(), r.raw()));
                    }
                }
            }
            log.info("KOSIS 미분양 {}행 ({} 이후)", out.size(), start);
            return out;
        });
    }

    @Bean
    Step kosisUnsoldStep(JobRepository repo, PlatformTransactionManager tm, ItemReader<SourceStat> kosisReader,
                         ItemProcessor<SourceStat, StatRow> regionStatProcessor, RegionRepository regions) {
        return statStep("kosisUnsoldStep", repo, tm, kosisReader, regionStatProcessor, regions);
    }

    // ---------------------------------------------------------------- roneIndexJob

    @Bean
    Job roneIndexJob(JobRepository repo, Step roneIndexStep, RunRecorder runs) {
        return new JobBuilder("roneIndexJob", repo).listener(runs.collect("RONE")).start(roneIndexStep).build();
    }

    /** 시리즈 × 월 × 페이지를 차례로 불러오는 Reader (한 번에 한 페이지만 메모리에) */
    @Bean
    @StepScope
    ItemReader<SourceStat> roneReader(RoneClient rone, RegionRepository regions, AppProperties props) {
        return new ItemReader<>() {
            private final Deque<SourceStat> buf = new ArrayDeque<>();
            private final Deque<String[]> plan = new ArrayDeque<>();   // seriesId, table, item, period
            private String[] cur;
            private int page;
            private int fetched;

            {
                YearMonth now = YearMonth.now(ApiQuotaService.KST);
                for (SeriesDef s : regions.series("RONE")) {
                    for (int m = props.rone().months(); m >= 0; m--) {
                        plan.add(new String[]{s.seriesId(), s.table(), s.item(), now.minusMonths(m).format(YM)});
                    }
                }
            }

            @Override
            public SourceStat read() {
                while (buf.isEmpty()) {
                    if (cur == null) {
                        cur = plan.poll();
                        if (cur == null) return null;
                        page = 0;
                        fetched = 0;
                    }
                    RoneClient.Page p = rone.data(cur[1], "MM", cur[3], ++page, 1000);
                    fetched += p.rows().size();
                    for (RoneClient.Row r : p.rows()) {
                        if (cur[2] != null && !cur[2].equals(r.itmId())) continue;
                        String path = r.clsFullNm() != null ? r.clsFullNm() : r.clsNm();
                        if (path == null || path.isBlank()) continue;      // 이름 없는 집계 행 (실제 응답에 3건)
                        List<String> parts = Arrays.stream(path.split(">")).map(String::trim).toList();
                        buf.add(new SourceStat(cur[0], "RONE", r.clsId(), parts.get(0), parts.subList(1, parts.size()),
                                r.period(), r.value(), null));
                    }
                    if (p.rows().isEmpty() || fetched >= p.total()) cur = null;
                }
                return buf.poll();
            }
        };
    }

    @Bean
    Step roneIndexStep(JobRepository repo, PlatformTransactionManager tm, ItemReader<SourceStat> roneReader,
                       ItemProcessor<SourceStat, StatRow> regionStatProcessor, RegionRepository regions) {
        return statStep("roneIndexStep", repo, tm, roneReader, regionStatProcessor, regions);
    }
}
