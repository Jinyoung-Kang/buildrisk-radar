package com.buildrisk.radar.batch.support;

import com.buildrisk.radar.adapters.common.ApiQuotaService;
import com.buildrisk.radar.adapters.dart.DartClient;
import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.domain.account.FsRepository;
import com.buildrisk.radar.domain.account.PeriodKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Job 실행 창구 (API · CLI · 스케줄러 공용).
 *   - 같은 Job 이 실행 중이면 409 JOB_ALREADY_RUNNING
 *   - 마지막 실행이 STOPPED·FAILED 면 restart (같은 JobInstance — 마지막 커밋 이후부터)
 *   - 아니면 runAt 파라미터로 새 JobInstance
 */
@Service
public class BatchLauncher {
    private static final Logger log = LoggerFactory.getLogger(BatchLauncher.class);

    private final JobOperator operator;
    private final JobRepository repository;
    private final Map<String, Job> jobs;
    private final FsRepository fs;
    private final ApiQuotaService quota;
    private final AppProperties props;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final DataSource dataSource;
    private final com.buildrisk.radar.domain.market.AptTradeRepository trades;
    private final com.buildrisk.radar.domain.filing.FilingRepository filings;

    public BatchLauncher(JobOperator operator, JobRepository repository, List<Job> jobList, FsRepository fs,
                         ApiQuotaService quota, AppProperties props, org.springframework.jdbc.core.simple.JdbcClient jdbc,
                         DataSource dataSource, com.buildrisk.radar.domain.market.AptTradeRepository trades,
                         com.buildrisk.radar.domain.filing.FilingRepository filings) {
        this.trades = trades;
        this.filings = filings;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.operator = operator;
        this.repository = repository;
        this.jobs = new java.util.LinkedHashMap<>();
        jobList.forEach(j -> this.jobs.put(j.getName(), j));
        this.fs = fs;
        this.quota = quota;
        this.props = props;
    }

    public record Launch(long jobExecutionId, String status, boolean restarted, Integer plannedCalls,
                         Integer dailyLimitHint, Integer usedToday, String warning) {}

    public Collection<String> jobNames() { return jobs.keySet(); }

    public boolean running(String jobName) { return !repository.findRunningJobExecutions(jobName).isEmpty(); }

    /** 마지막 하트비트: Job·Step 실행의 last_updated 중 가장 최근 (Step 은 청크 커밋마다 갱신) */
    public java.time.LocalDateTime heartbeat(JobExecution je) {
        java.time.LocalDateTime hb = je.getLastUpdated();
        for (var se : je.getStepExecutions()) {
            if (se.getLastUpdated() != null && (hb == null || se.getLastUpdated().isAfter(hb))) hb = se.getLastUpdated();
        }
        return hb;
    }

    public boolean stale(JobExecution je) {
        var hb = heartbeat(je);
        return hb != null && hb.isBefore(java.time.LocalDateTime.now().minusMinutes(props.batch().staleMinutes()));
    }

    /**
     * 프로세스가 죽어 STARTED 로 남은 실행(좀비)을 FAILED 로 정리 → 다음 실행이 restart 로 이어갈 수 있게 합니다.
     * force=false 면 하트비트가 staleMinutes 넘게 멈춘 실행만.
     */
    public List<Long> recover(String jobName, boolean force) {
        List<Long> out = new ArrayList<>();
        for (JobExecution je : repository.findRunningJobExecutions(jobName)) {
            if (force || stale(je)) {
                log.warn("{} 실행 {} 정리 (하트비트 {}) → FAILED", jobName, je.getId(), heartbeat(je));
                operator.recover(je);
                noteRecovered(je.getId(), "하트비트 " + heartbeat(je) + " 이후 멈춤 — 자동 정리");
                out.add(je.getId());
            }
        }
        return out;
    }

    public List<Long> recoverExecution(long executionId) {
        JobExecution je = repository.getJobExecution(executionId);
        if (je == null) throw new ApiException(ErrorCode.NOT_FOUND, "실행 이력이 없습니다: " + executionId);
        if (!je.isRunning()) throw new ApiException(ErrorCode.VALIDATION_ERROR, "실행 중 상태가 아닙니다: " + je.getStatus());
        operator.recover(je);
        noteRecovered(executionId, "관리자가 수동 정리 (마지막 하트비트 " + heartbeat(je) + ")");
        return List.of(executionId);
    }

    /** 실행 전 검증·계획 — 잘못된 파라미터는 큐에 넣기 전에 400 으로 거절 (API 역할에서 호출) */
    public record Plan(JobParameters params, Integer plannedCalls, Integer dailyLimitHint, Integer usedToday, String warning,
                       boolean allowRestart) {}

    public Plan plan(String jobName, Map<String, Object> body) {
        if (!jobs.containsKey(jobName)) throw new ApiException(ErrorCode.JOB_NOT_FOUND, "Job 이 없습니다: " + jobName);
        Map<String, Object> b = body == null ? Map.of() : body;
        JobParametersBuilder pb = new JobParametersBuilder().addLong(BatchKeys.RUN_AT, System.currentTimeMillis());
        Integer planned = null, limit = null, used = null;
        String warning = null;
        try {
            switch (jobName) {
                case "financialStatementJob" -> {
                    int from = props.dart().fromYear(), to = ApiQuotaService.today().getYear();
                    if (b.get("years") instanceof List<?> ys && !ys.isEmpty()) {
                        from = ys.stream().mapToInt(y -> Integer.parseInt(String.valueOf(y))).min().getAsInt();
                        to = ys.stream().mapToInt(y -> Integer.parseInt(String.valueOf(y))).max().getAsInt();
                    }
                    List<String> reprts = b.get("reprtCodes") instanceof List<?> rs && !rs.isEmpty()
                            ? rs.stream().map(String::valueOf).toList() : PeriodKeys.REPRT_CODES;
                    reprts.forEach(PeriodKeys::quarterOf);   // 검증
                    if (b.get("fsDivOrder") instanceof List<?> order && !order.equals(List.of("CFS", "OFS"))) {
                        throw new ApiException(ErrorCode.VALIDATION_ERROR, "fsDivOrder 는 [\"CFS\",\"OFS\"] 만 지원합니다.");
                    }
                    planned = fs.pendingTargets(from, to, reprts, ApiQuotaService.today()).size();
                    limit = props.dart().dailyCallLimit();
                    used = quota.used(DartClient.PROVIDER);
                    int remaining = Math.max(0, limit - used);
                    pb.addLong("fromYear", (long) from).addLong("toYear", (long) to)
                            .addString("reprtCodes", String.join(",", reprts));
                    Long max = b.get("maxCalls") == null ? null : Long.parseLong(String.valueOf(b.get("maxCalls")));
                    if (planned > remaining) {
                        warning = "미수집 조합 " + planned + "건이 오늘 남은 DART 호출 " + remaining
                                + "건보다 많아 상한까지만 실행하고 STOPPED 로 멈춥니다. 다음 실행에서 이어갑니다.";
                        max = max == null ? remaining : Math.min(max, remaining);
                    }
                    if (max != null) pb.addLong("maxCalls", max);
                }
                case "aptTradeJob" -> {
                    int months = b.get("months") == null ? props.dataGoKr().tradeMonths() : Integer.parseInt(String.valueOf(b.get("months")));
                    if (months < 13 || months > 120) throw new ApiException(ErrorCode.VALIDATION_ERROR, "months 는 13~120");
                    var w = com.buildrisk.radar.domain.market.TradeWindow.of(java.time.YearMonth.now(ApiQuotaService.KST), months);
                    planned = trades.pending(w.fromYm(), w.toYm(), w.refreshFromYm(), ApiQuotaService.today()).size();
                    limit = props.dataGoKr().dailyCallLimit();
                    used = quota.used(com.buildrisk.radar.adapters.datagokr.RtmsClient.PROVIDER);
                    pb.addLong("months", (long) months);
                    Long max = b.get("maxCalls") == null ? null : Long.parseLong(String.valueOf(b.get("maxCalls")));
                    int remaining = Math.max(0, limit - used);
                    if (planned > remaining) {
                        warning = "수집 대상 " + planned + "건이 오늘 남은 실거래 API 호출 " + remaining
                                + "건보다 많아 상한까지만 받고 STOPPED 로 멈춥니다. 다음 실행에서 이어갑니다(최근 달부터 채움).";
                        max = max == null ? remaining : Math.min(max, remaining);
                    }
                    if (max != null) pb.addLong("maxCalls", max);
                }
                case "filingParseJob" -> {
                    long days = b.get("days") == null ? 400L : Long.parseLong(String.valueOf(b.get("days")));
                    if (days < 1 || days > 3650) throw new ApiException(ErrorCode.VALIDATION_ERROR, "days 는 1~3650");
                    planned = filings.pending(ApiQuotaService.today().minusDays(days)).size();
                    limit = props.dart().dailyCallLimit();
                    used = quota.used(DartClient.PROVIDER);
                    pb.addLong("days", days);
                    Long max = b.get("maxCalls") == null ? null : Long.parseLong(String.valueOf(b.get("maxCalls")));
                    int remaining = Math.max(0, limit - used);
                    if (planned > remaining) {
                        warning = "원문 " + planned + "건이 오늘 남은 DART 호출 " + remaining + "건보다 많아 상한까지만 받습니다.";
                        max = max == null ? remaining : Math.min(max, remaining);
                    }
                    if (max != null) pb.addLong("maxCalls", max);
                }
                case "stockPriceJob" -> {
                    if (b.get("years") != null) {
                        long years = Long.parseLong(String.valueOf(b.get("years")));
                        if (years < 1 || years > 10) throw new ApiException(ErrorCode.VALIDATION_ERROR, "years 는 1~10");
                        pb.addLong("years", years);
                    }
                    limit = props.dataGoKr().dailyCallLimit();
                    used = quota.used(com.buildrisk.radar.adapters.datagokr.StockPriceClient.PROVIDER);
                }
                case "disclosureSyncJob" -> pb.addLong("days", b.get("days") == null ? 7L : Long.parseLong(String.valueOf(b.get("days"))));
                case "sgisHouseholdJob" -> {
                    if (b.get("year") != null) pb.addLong("year", Long.parseLong(String.valueOf(b.get("year"))));
                }
                case "boundaryLoadJob" -> {
                    if (b.get("source") != null) {
                        String src = String.valueOf(b.get("source")).toUpperCase();
                        if (!List.of("VWORLD", "SGIS").contains(src)) throw new ApiException(ErrorCode.VALIDATION_ERROR, "source 는 VWORLD · SGIS");
                        pb.addString("source", src);
                    }
                    if (b.get("year") != null) pb.addLong("year", Long.parseLong(String.valueOf(b.get("year"))));
                }
                default -> { }
            }
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "숫자 파라미터 형식이 올바르지 않습니다: " + e.getMessage());
        }
        return new Plan(pb.toJobParameters(), planned, limit, used, warning, !Boolean.FALSE.equals(b.get("restart")));
    }

    /**
     * 실행 — 프로세스 간 중복 실행을 PostgreSQL advisory lock 으로 막습니다 (worker · CLI 가 동시에 같은 Job 을 시작하는 경쟁).
     * 잠금 구간: 멈춘 실행 정리 → 실행 중 확인 → start/restart(실행 레코드 생성) 까지. 실행 자체는 잠금 밖에서 비동기로 진행.
     */
    public Launch launch(String jobName, Map<String, Object> body) {
        Plan plan = plan(jobName, body);
        Job job = jobs.get(jobName);
        long key = ("buildrisk:batch:" + jobName).hashCode();
        try (Connection c = dataSource.getConnection()) {
            if (!tryLock(c, key)) throw new ApiException(ErrorCode.JOB_ALREADY_RUNNING, jobName + " 을(를) 다른 프로세스가 시작하는 중입니다.");
            try {
                recover(jobName, false);
                if (running(jobName)) throw new ApiException(ErrorCode.JOB_ALREADY_RUNNING, jobName + " 이(가) 이미 실행 중입니다.");
                JobExecution last = lastExecution(jobName);
                if (last != null && plan.allowRestart() && (last.getStatus() == BatchStatus.STOPPED || last.getStatus() == BatchStatus.FAILED)) {
                    JobExecution je = withConflictRetry(() -> operator.restart(last));
                    log.info("{} 재시작: 이전 실행 {} ({}) → {}", jobName, last.getId(), last.getStatus(), je.getId());
                    return new Launch(je.getId(), je.getStatus().name(), true, null, null, null, null);
                }
                JobExecution je = withConflictRetry(() -> operator.start(job, plan.params()));
                return new Launch(je.getId(), je.getStatus().name(), false, plan.plannedCalls(), plan.dailyLimitHint(),
                        plan.usedToday(), plan.warning());
            } finally {
                unlock(c, key);
            }
        } catch (ApiException e) {
            throw e;
        } catch (org.springframework.batch.core.launch.JobExecutionAlreadyRunningException e) {
            throw new ApiException(ErrorCode.JOB_ALREADY_RUNNING, jobName + " 이(가) 이미 실행 중입니다.");
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, jobName + " 실행 실패: " + e.getMessage());
        }
    }

    interface Starter { JobExecution start() throws Exception; }

    /** JobRepository 메타 테이블의 동시성 충돌(직렬화 실패·교착)은 짧게 물러났다가 최대 3번 */
    static JobExecution withConflictRetry(Starter s) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return s.start();
            } catch (org.springframework.dao.ConcurrencyFailureException e) {
                if (attempt >= 3) throw e;
                log.warn("Job 실행 레코드 생성 충돌 — {}번째 재시도: {}", attempt, e.getMostSpecificCause().getMessage());
                Thread.sleep(100L * attempt + java.util.concurrent.ThreadLocalRandom.current().nextLong(100));
            }
        }
    }

    private static boolean tryLock(Connection c, long key) throws java.sql.SQLException {
        try (var ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, key);
            try (var rs = ps.executeQuery()) { return rs.next() && rs.getBoolean(1); }
        }
    }

    private static void unlock(Connection c, long key) throws java.sql.SQLException {
        try (var ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, key);
            ps.execute();
        }
    }

    /** CLI·스케줄러용: 실행하고 끝날 때까지 기다림 */
    public JobExecution runAndWait(String jobName, Map<String, Object> body) {
        Launch l = launch(jobName, body);
        if (l.warning() != null) log.warn(l.warning());
        JobExecution je;
        do {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            je = repository.getJobExecution(l.jobExecutionId());
        } while (je != null && je.isRunning());
        return je;
    }

    /** 정리된 실행의 종료 메시지에 사유를 남김 — 배치 모니터에서 '왜 FAILED 인지' 보이도록 */
    private void noteRecovered(long executionId, String why) {
        jdbc.sql("""
                UPDATE ops.batch_job_execution SET exit_message = :m || coalesce(E'\n' || nullif(exit_message, ''), '')
                WHERE job_execution_id = :id""")
                .param("m", "원인: 프로세스 비정상 종료로 STARTED 에 남은 실행을 정리함 · " + why + ". 다음 실행이 같은 JobInstance 를 restart 합니다.")
                .param("id", executionId).update();
    }

    public JobExecution lastExecution(String jobName) {
        JobInstance inst = repository.getLastJobInstance(jobName);
        return inst == null ? null : repository.getLastJobExecution(inst);
    }

    public List<String> ordered(String spec) {
        if (spec == null || spec.isBlank() || spec.equals("all")) {
            return JobCatalog.JOBS.stream().map(JobCatalog.Def::name).toList();
        }
        List<String> out = new ArrayList<>();
        for (String s : spec.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }
}
