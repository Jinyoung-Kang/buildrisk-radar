package com.buildrisk.radar.api;

import com.buildrisk.radar.adapters.common.ApiQuotaService;
import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.batch.support.JobCatalog;
import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** FR-603 배치 모니터 — Spring Batch 메타 테이블(ops.BATCH_*) + ops.skip_log */
@Service
public class BatchQueryService {
    private final JdbcClient jdbc;
    private final BatchLauncher launcher;
    private final Environment env;
    private final AppProperties props;

    public BatchQueryService(JdbcClient jdbc, BatchLauncher launcher, Environment env, AppProperties props) {
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
                   i.job_instance_id AS "jobInstanceId"
            FROM ops.batch_job_execution e JOIN ops.batch_job_instance i ON i.job_instance_id = e.job_instance_id
            """;

    public List<Map<String, Object>> executions(String jobName, int limit) {
        return jdbc.sql(EXEC + " WHERE cast(:j AS text) IS NULL OR i.job_name = :j ORDER BY e.job_execution_id DESC LIMIT :l")
                .param("j", CompanyQueryService.blank(jobName)).param("l", Math.max(1, Math.min(limit, 200))).query().listOfRows();
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

    public Map<String, Object> jobs() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (JobCatalog.Def d : JobCatalog.JOBS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.name());
            m.put("title", d.title());
            m.put("source", d.source());
            m.put("schedule", d.schedule());
            m.put("requirement", d.requirement());
            m.put("keyConfigured", d.envKey() == null || !blank(env.getProperty(d.envKey())));
            m.put("envKey", d.envKey());
            m.put("running", launcher.running(d.name()));
            var last = launcher.lastExecution(d.name());
            if (last != null && last.isRunning()) {
                m.put("heartbeat", String.valueOf(launcher.heartbeat(last)));
                m.put("stale", launcher.stale(last));
            }
            m.put("last", last == null ? null : Map.of("jobExecutionId", last.getId(), "status", last.getStatus().name(),
                    "startTime", String.valueOf(last.getStartTime()), "endTime", String.valueOf(last.getEndTime())));
            jobs.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobs", jobs);
        out.put("quota", jdbc.sql("SELECT provider, calls FROM ops.api_quota WHERE day = :d ORDER BY provider")
                .param("d", ApiQuotaService.today()).query().listOfRows());
        out.put("dartDailyLimit", props.dart().dailyCallLimit());
        out.put("schedulingEnabled", props.batch().schedulingEnabled());
        return out;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
