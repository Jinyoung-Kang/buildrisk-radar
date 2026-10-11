package com.buildrisk.radar.batch.queue;

import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.common.KeyMasker;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 배치 실행 요청 큐 (ops.job_request, ADR-012).
 * API 는 검증(plan) 후 요청만 넣고 202 를 돌려주며, Worker 가 SKIP LOCKED 로 하나씩 가져가 실행합니다.
 * 같은 Job 의 대기·실행 중 요청은 부분 유니크 인덱스로 하나만 — 중복 클릭·재시도에도 멱등(409).
 * params._next: 완료되면 이어서 넣을 Job 목록 (예: 지표 → 규칙 평가)
 */
@Service
public class JobRequestService {
    private static final Logger log = LoggerFactory.getLogger(JobRequestService.class);
    public static final String NEXT = "_next";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final BatchLauncher launcher;
    private final ObjectMapper mapper;

    public JobRequestService(JdbcClient jdbc, BatchLauncher launcher, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.launcher = launcher;
        this.mapper = mapper;
    }

    public record Request(long requestId, String jobName, Map<String, Object> params, String requestedBy, String status,
                          Long jobExecutionId, String batchStatus, String message) {}

    public record Enqueued(long requestId, String jobName, String status, Integer plannedCalls, Integer dailyLimitHint,
                           Integer usedToday, String warning) {}

    public Enqueued enqueue(String jobName, Map<String, Object> params, String requestedBy) {
        Map<String, Object> p = params == null ? Map.of() : params;
        BatchLauncher.Plan plan = launcher.plan(jobName, p);         // 잘못된 요청은 여기서 400
        launcher.recoverStale(jobName);                              // 죽은 worker 가 남긴 실행은 '실행 중'이 아님
        if (launcher.running(jobName)) {
            throw new ApiException(ErrorCode.JOB_ALREADY_RUNNING, jobName + " 이(가) 이미 실행 중입니다.");
        }
        try {
            long id = jdbc.sql("""
                            INSERT INTO ops.job_request (job_name, params, requested_by)
                            VALUES (:j, cast(:p AS jsonb), :by) RETURNING request_id""")
                    .param("j", jobName).param("p", mapper.writeValueAsString(p)).param("by", requestedBy)
                    .query(Long.class).single();
            return new Enqueued(id, jobName, "QUEUED", plan.plannedCalls(), plan.dailyLimitHint(), plan.usedToday(), plan.warning());
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.JOB_ALREADY_RUNNING, jobName + " 은(는) 이미 대기 중이거나 실행 중입니다.");
        }
    }

    /** 대기 요청 하나를 가져감 — 여러 Worker 가 떠 있어도 같은 요청을 두 번 가져가지 않음 */
    public Optional<Request> claim(String workerId) {
        return jdbc.sql("""
                        UPDATE ops.job_request SET status = 'RUNNING', claimed_by = :w, claimed_at = now()
                        WHERE request_id = (SELECT request_id FROM ops.job_request WHERE status = 'QUEUED'
                                            ORDER BY request_id FOR UPDATE SKIP LOCKED LIMIT 1)
                        RETURNING request_id, job_name, params::text, requested_by, status, job_execution_id, batch_status, message""")
                .param("w", workerId).query(this::row).optional();
    }

    public void started(long requestId, long jobExecutionId) {
        jdbc.sql("UPDATE ops.job_request SET job_execution_id = :e WHERE request_id = :id")
                .param("e", jobExecutionId).param("id", requestId).update();
    }

    public void finished(Request r, String status, String batchStatus, String message) {
        finish(r, status, batchStatus, message, false);
    }

    /** 아직 RUNNING 일 때만 마무리 — 정리 주기(reconcile)가 그 사이 worker 가 기록한 결과를 덮어쓰지 않게. 마무리했으면 true */
    public boolean finishedIfRunning(Request r, String status, String batchStatus, String message) {
        return finish(r, status, batchStatus, message, true);
    }

    /** 이 worker 가 가져갔다고 적힌 RUNNING 요청 (VERIFICATION 182) */
    public List<Request> runningClaimedBy(String workerId) {
        return jdbc.sql("""
                        SELECT request_id, job_name, params::text, requested_by, status, job_execution_id, batch_status, message
                        FROM ops.job_request WHERE status = 'RUNNING' AND claimed_by = :w ORDER BY request_id""")
                .param("w", workerId).query(this::row).list();
    }

    private boolean finish(Request r, String status, String batchStatus, String message, boolean onlyIfRunning) {
        int n = jdbc.sql("""
                UPDATE ops.job_request SET status = :s, batch_status = :b, message = :m, finished_at = now()
                WHERE request_id = :id""" + (onlyIfRunning ? " AND status = 'RUNNING'" : ""))
                // worker 의 예외 메시지 원문이 들어올 수 있음 — 외부 API 주소의 인증키를 가려 저장 (VERIFICATION 177)
                .param("s", status).param("b", batchStatus).param("m", KeyMasker.mask(message)).param("id", r.requestId()).update();
        if (n == 0) return false;
        if ("COMPLETED".equals(batchStatus) && r.params().get(NEXT) instanceof List<?> next && !next.isEmpty()) {
            Map<String, Object> rest = new LinkedHashMap<>();
            if (next.size() > 1) rest.put(NEXT, next.subList(1, next.size()));
            try {
                enqueue(String.valueOf(next.get(0)), rest, "chain:#" + r.requestId());
            } catch (ApiException e) {
                // 이미 대기 · 실행 중이면 건너뜀 — 실행 중이었다면 그 실행은 이번 결과 전의 값을 봤을 수 있어 남겨 둠 (VERIFICATION 126)
                log.warn("요청 #{} 다음 Job {} 을 이어 넣지 못함: {}", r.requestId(), next.get(0), e.getMessage());
            }
        }
        return true;
    }

    /** Worker 가 재시작되면 이전 프로세스가 가져간 채 끝나지 않은 요청을 FAILED 로 정리 (실행 자체는 하트비트 정리가 담당) */
    public int abandonRunning(String reason) {
        return jdbc.sql("""
                UPDATE ops.job_request SET status = 'FAILED', message = :m, finished_at = now()
                WHERE status = 'RUNNING'""").param("m", reason).update();
    }

    public List<Map<String, Object>> recent(int limit) {
        return jdbc.sql("""
                SELECT request_id AS "requestId", job_name AS "jobName", params::text AS "params", requested_by AS "requestedBy",
                       requested_at AS "requestedAt", status, claimed_by AS "claimedBy", job_execution_id AS "jobExecutionId",
                       batch_status AS "batchStatus", finished_at AS "finishedAt", message
                FROM ops.job_request ORDER BY request_id DESC LIMIT :l""")
                .param("l", Math.max(1, Math.min(limit, 200))).query().listOfRows();
    }

    public Map<String, Object> get(long id) {
        return jdbc.sql("""
                SELECT request_id AS "requestId", job_name AS "jobName", status, job_execution_id AS "jobExecutionId",
                       batch_status AS "batchStatus", message, requested_at AS "requestedAt", finished_at AS "finishedAt"
                FROM ops.job_request WHERE request_id = :id""").param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "실행 요청이 없습니다: " + id));
    }

    private Request row(ResultSet rs, int i) throws SQLException {
        // 실행 번호가 없으면 null — wasNull() 은 마지막에 읽은 열을 보므로 getLong 바로 뒤에서 (예전에는 status 를 봐 0 이 됐음)
        Long exec = rs.getObject(6, Long.class);
        return new Request(rs.getLong(1), rs.getString(2), mapper.readValue(rs.getString(3), MAP), rs.getString(4),
                rs.getString(5), exec, rs.getString(7), rs.getString(8));
    }
}
