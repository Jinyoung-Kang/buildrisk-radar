package com.buildrisk.radar.ops.app;

import com.buildrisk.radar.batch.queue.WorkerRegistry;
import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.common.Kst;
import com.buildrisk.radar.common.Text;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.ops.domain.JobCatalog;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** FR-603 배치 모니터 — Spring Batch 메타 테이블(ops.BATCH_*) + ops.skip_log */
@Service
public class BatchQueryService {
    private final JdbcClient jdbc;
    private final BatchLauncher launcher;
    private final Environment env;
    private final AppProperties props;

    private final WorkerRegistry workers;

    public BatchQueryService(JdbcClient jdbc, BatchLauncher launcher, Environment env, AppProperties props,
                             WorkerRegistry workers) {
        this.workers = workers;
        this.jdbc = jdbc;
        this.launcher = launcher;
        this.env = env;
        this.props = props;
    }

    private static final String EXEC = """
            SELECT e.job_execution_id AS "jobExecutionId", i.job_name AS "jobName", e.status, e.exit_code AS "exitCode",
                   left(e.exit_message, 600) AS "exitMessage", e.create_time AS "createTime", e.start_time AS "startTime",
                   e.end_time AS "endTime",
                   round(extract(epoch FROM (coalesce(e.end_time, now()::timestamp) - e.start_time))::numeric, 1) AS "durationSec",
                   (SELECT string_agg(p.parameter_name || '=' || coalesce(p.parameter_value, ''), ', ' ORDER BY p.parameter_name)
                      FROM ops.batch_job_execution_params p WHERE p.job_execution_id = e.job_execution_id) AS "params",
                   (SELECT coalesce(sum(s.read_count), 0) FROM ops.batch_step_execution s WHERE s.job_execution_id = e.job_execution_id) AS "readCount",
                   (SELECT coalesce(sum(s.write_count), 0) FROM ops.batch_step_execution s WHERE s.job_execution_id = e.job_execution_id) AS "writeCount",
                   (SELECT coalesce(sum(s.filter_count), 0) FROM ops.batch_step_execution s WHERE s.job_execution_id = e.job_execution_id) AS "filterCount",
                   (SELECT coalesce(sum(s.commit_count), 0) FROM ops.batch_step_execution s WHERE s.job_execution_id = e.job_execution_id) AS "commitCount",
                   (SELECT count(*) FROM ops.skip_log k WHERE k.job_execution_id = e.job_execution_id) AS "skipLogCount",
                   i.job_instance_id AS "jobInstanceId",
                   (SELECT min(x.job_execution_id) FROM ops.batch_job_execution x
                     WHERE x.job_instance_id = e.job_instance_id AND x.job_execution_id > e.job_execution_id
                       AND x.status = 'COMPLETED') AS "resolvedBy"
            FROM ops.batch_job_execution e JOIN ops.batch_job_instance i ON i.job_instance_id = e.job_instance_id
            """;

    public List<Map<String, Object>> executions(String jobName, int limit) {
        return jdbc.sql(EXEC + " WHERE cast(:j AS text) IS NULL OR i.job_name = :j ORDER BY e.job_execution_id DESC LIMIT :l")
                .param("j", Text.blank(jobName)).param("l", Math.max(1, Math.min(limit, 200))).query().listOfRows();
    }

    public Map<String, Object> execution(long id) {
        Map<String, Object> e = jdbc.sql(EXEC + " WHERE e.job_execution_id = :id").param("id", id).query().listOfRows()
                .stream().findFirst().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "실행 이력이 없습니다: " + id));
        Map<String, Object> out = new LinkedHashMap<>(e);
        out.put("steps", jdbc.sql("""
                SELECT step_name AS "stepName", status, read_count AS "readCount", write_count AS "writeCount",
                       filter_count AS "filterCount", commit_count AS "commitCount", rollback_count AS "rollbackCount",
                       read_skip_count + process_skip_count + write_skip_count AS "skipCount", exit_code AS "exitCode",
                       left(exit_message, 600) AS "exitMessage", start_time AS "startTime", end_time AS "endTime"
                FROM ops.batch_step_execution WHERE job_execution_id = :id ORDER BY step_execution_id""")
                .param("id", id).query().listOfRows());
        out.put("skips", jdbc.sql("""
                SELECT step_name AS "stepName", item_key AS "itemKey", reason_code AS "reasonCode", message, created_at AS "createdAt"
                FROM ops.skip_log WHERE job_execution_id = :id ORDER BY skip_id DESC LIMIT 300""")
                .param("id", id).query().listOfRows());
        out.put("runStats", jdbc.sql("""
                SELECT coalesce((SELECT stats::text FROM ops.collect_run WHERE job_execution_id = :id),
                                (SELECT stats::text FROM ops.calc_run WHERE job_execution_id = :id))""")
                .param("id", id).query(String.class).optional().orElse(null));
        out.put("sameInstance", jdbc.sql("""
                SELECT job_execution_id AS "jobExecutionId", status, start_time AS "startTime", end_time AS "endTime"
                FROM ops.batch_job_execution WHERE job_instance_id = :i ORDER BY job_execution_id""")
                .param("i", e.get("jobInstanceId")).query().listOfRows());
        return out;
    }

    /** Spring Batch 의 BatchStatus.isRunning() 과 같은 상태 */
    private static final Set<String> RUNNING = Set.of("STARTING", "STARTED", "STOPPING");

    /** Job 하나의 마지막 실행 · 실행 중 여부 · 하트비트 · 대기 요청 */
    private record JobState(String name, boolean running, Long queued, Long lastId, String lastStatus, LocalDateTime lastStart,
                            LocalDateTime lastEnd, LocalDateTime heartbeat) {}

    /**
     * 모든 Job 의 상태를 한 번에 — Spring Batch 저장소와 같은 정의: 마지막 실행 = 가장 큰 JobInstance 의 가장 큰 실행, 실행 중 = 그 이름의
     * 어떤 실행이든 STARTING · STARTED · STOPPING, 하트비트 = 실행 중인 마지막 실행의 Job · Step last_updated 중 최근(BatchLauncher.heartbeat).
     * Job 마다 저장소를 여러 번 부르면 호출 1회에 테이블 스캔 약 220회 — 익명으로 부를 수 있고 화면이 3초마다 부름 (VERIFICATION 193)
     */
    private Map<String, JobState> jobStates(List<String> names) {
        return jdbc.sql("""
                        WITH n AS (SELECT unnest(cast(:names AS text[])) AS job_name),
                        li AS (SELECT job_name, max(job_instance_id) AS inst FROM ops.batch_job_instance
                                WHERE job_name = ANY (cast(:names AS text[])) GROUP BY job_name),
                        le AS (SELECT li.job_name, max(e.job_execution_id) AS exec
                                 FROM li JOIN ops.batch_job_execution e ON e.job_instance_id = li.inst GROUP BY li.job_name),
                        run AS (SELECT DISTINCT i.job_name FROM ops.batch_job_execution e
                                  JOIN ops.batch_job_instance i ON i.job_instance_id = e.job_instance_id
                                 WHERE i.job_name = ANY (cast(:names AS text[])) AND e.status IN ('STARTING', 'STARTED', 'STOPPING')),
                        q AS (SELECT job_name, request_id FROM ops.job_request WHERE status = 'QUEUED' AND job_name = ANY (cast(:names AS text[])))
                        SELECT n.job_name, run.job_name IS NOT NULL, q.request_id, e.job_execution_id, e.status, e.start_time, e.end_time,
                               CASE WHEN e.status IN ('STARTING', 'STARTED', 'STOPPING') THEN greatest(e.last_updated,
                                   (SELECT max(s.last_updated) FROM ops.batch_step_execution s WHERE s.job_execution_id = e.job_execution_id)) END
                          FROM n LEFT JOIN le ON le.job_name = n.job_name
                                 LEFT JOIN ops.batch_job_execution e ON e.job_execution_id = le.exec
                                 LEFT JOIN run ON run.job_name = n.job_name
                                 LEFT JOIN q ON q.job_name = n.job_name""")
                .param("names", names.toArray(String[]::new))
                .query((rs, i) -> new JobState(rs.getString(1), rs.getBoolean(2), rs.getObject(3, Long.class), rs.getObject(4, Long.class),
                        rs.getString(5), rs.getObject(6, LocalDateTime.class), rs.getObject(7, LocalDateTime.class),
                        rs.getObject(8, LocalDateTime.class)))
                .list().stream().collect(Collectors.toMap(JobState::name, x -> x));
    }

    public Map<String, Object> jobs() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        Map<String, Boolean> liveKeys = workers.liveKeyStatus();   // API 는 키가 없으므로 살아 있는 worker 의 보고로
        Map<String, JobState> states = jobStates(JobCatalog.JOBS.stream().map(JobCatalog.Def::name).toList());
        for (JobCatalog.Def d : JobCatalog.JOBS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.name());
            m.put("title", d.title());
            m.put("source", d.source());
            m.put("schedule", d.schedule());
            m.put("requirement", d.requirement());
            m.put("keyConfigured", d.envKey() == null || (liveKeys != null ? liveKeys.getOrDefault(d.envKey(), false)
                    : !blank(env.getProperty(d.envKey()))));
            m.put("envKey", d.envKey());
            JobState st = states.get(d.name());
            m.put("running", st != null && st.running());
            m.put("queued", st == null ? null : st.queued());
            if (st != null && st.lastId() != null && RUNNING.contains(st.lastStatus())) {
                m.put("heartbeat", String.valueOf(st.heartbeat()));
                m.put("stale", launcher.stale(st.heartbeat()));
            }
            m.put("last", st == null || st.lastId() == null ? null : Map.of("jobExecutionId", st.lastId(), "status", st.lastStatus(),
                    "startTime", String.valueOf(st.lastStart()), "endTime", String.valueOf(st.lastEnd())));
            jobs.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobs", jobs);
        out.put("workers", workers.list());
        out.put("quota", jdbc.sql("SELECT provider, calls FROM ops.api_quota WHERE day = :d ORDER BY provider")
                .param("d", Kst.today()).query().listOfRows());
        out.put("dartDailyLimit", props.dart().dailyCallLimit());
        out.put("schedulingEnabled", props.batch().schedulingEnabled());
        return out;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
