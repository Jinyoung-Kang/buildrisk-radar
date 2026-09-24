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

    public BatchLauncher(JobOperator operator, JobRepository repository, List<Job> jobList, FsRepository fs,
                         ApiQuotaService quota, AppProperties props, org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.jdbc = jdbc;
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

    public Launch launch(String jobName, Map<String, Object> body) {
        Job job = jobs.get(jobName);
        if (job == null) throw new ApiException(ErrorCode.JOB_NOT_FOUND, "Job 이 없습니다: " + jobName);
        recover(jobName, false);
        if (running(jobName)) throw new ApiException(ErrorCode.JOB_ALREADY_RUNNING, jobName + " 이(가) 이미 실행 중입니다.");
        Map<String, Object> b = body == null ? Map.of() : body;
        try {
            JobExecution last = lastExecution(jobName);
            boolean wantRestart = !Boolean.FALSE.equals(b.get("restart"));
            if (last != null && wantRestart && (last.getStatus() == BatchStatus.STOPPED || last.getStatus() == BatchStatus.FAILED)) {
                JobExecution je = operator.restart(last);
                log.info("{} 재시작: 이전 실행 {} ({}) → {}", jobName, last.getId(), last.getStatus(), je.getId());
                return new Launch(je.getId(), je.getStatus().name(), true, null, null, null, null);
            }
            JobParametersBuilder pb = new JobParametersBuilder().addLong(BatchKeys.RUN_AT, System.currentTimeMillis());
            Integer planned = null, limit = null, used = null;
            String warning = null;
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
                case "disclosureSyncJob" -> pb.addLong("days", b.get("days") == null ? 7L : Long.parseLong(String.valueOf(b.get("days"))));
                case "sgisHouseholdJob" -> {
                    if (b.get("year") != null) pb.addLong("year", Long.parseLong(String.valueOf(b.get("year"))));
                }
                case "boundaryLoadJob" -> {
                    if (b.get("source") != null) pb.addString("source", String.valueOf(b.get("source")).toUpperCase());
                    if (b.get("year") != null) pb.addLong("year", Long.parseLong(String.valueOf(b.get("year"))));
                }
                default -> { }
            }
            JobParameters params = pb.toJobParameters();
            JobExecution je = operator.start(job, params);
            return new Launch(je.getId(), je.getStatus().name(), false, planned, limit, used, warning);
        } catch (ApiException e) {
            throw e;
        } catch (org.springframework.batch.core.launch.JobExecutionAlreadyRunningException e) {
            throw new ApiException(ErrorCode.JOB_ALREADY_RUNNING, jobName + " 이(가) 이미 실행 중입니다.");
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, jobName + " 실행 실패: " + e.getMessage());
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
