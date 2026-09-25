package com.buildrisk.radar.batch.queue;

import com.buildrisk.radar.batch.support.JobCatalog;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * worker 등록부 — 하트비트와 '설정된 키 이름'만 기록 (값은 절대 저장하지 않음).
 * 하트비트가 LIVE_SECONDS 넘게 끊긴 worker 는 죽은 것으로 보고, 그 worker 가 잡고 있던 요청만 정리합니다(다중 worker 안전).
 */
@Component
public class WorkerRegistry {
    public static final int LIVE_SECONDS = 60;
    private final JdbcClient jdbc;
    private final Environment env;

    public WorkerRegistry(JdbcClient jdbc, Environment env) {
        this.jdbc = jdbc;
        this.env = env;
    }

    public record Worker(String workerId, String startedAt, String lastSeenAt, List<String> configuredKeys, int inFlight,
                         int maxConcurrent, boolean live) {}

    /** 이 프로세스가 가진 외부 API 키 이름 (값이 비어 있지 않은 것) */
    public List<String> configuredKeys() {
        return JobCatalog.JOBS.stream().map(JobCatalog.Def::envKey).filter(Objects::nonNull).distinct()
                .filter(k -> { String v = env.getProperty(k); return v != null && !v.isBlank(); }).sorted().toList();
    }

    public void heartbeat(String workerId, int inFlight, int maxConcurrent) {
        jdbc.sql("""
                INSERT INTO ops.worker (worker_id, configured_keys, in_flight, max_concurrent) VALUES (:id, :keys, :f, :m)
                ON CONFLICT (worker_id) DO UPDATE SET last_seen_at = now(), configured_keys = EXCLUDED.configured_keys,
                  in_flight = EXCLUDED.in_flight, max_concurrent = EXCLUDED.max_concurrent""")
                .param("id", workerId).param("keys", configuredKeys().toArray(String[]::new))
                .param("f", inFlight).param("m", maxConcurrent).update();
    }

    public List<Worker> list() {
        return jdbc.sql("""
                SELECT worker_id, to_char(started_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD"T"HH24:MI:SS'),
                       to_char(last_seen_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD"T"HH24:MI:SS'), configured_keys, in_flight,
                       max_concurrent, last_seen_at > now() - make_interval(secs => :live)
                  FROM ops.worker WHERE last_seen_at > now() - interval '1 day' ORDER BY last_seen_at DESC""")
                .param("live", LIVE_SECONDS)
                .query((rs, i) -> new Worker(rs.getString(1), rs.getString(2), rs.getString(3),
                        List.of((String[]) rs.getArray(4).getArray()), rs.getInt(5), rs.getInt(6), rs.getBoolean(7))).list();
    }

    /** 살아 있는 worker 중 하나라도 이 키를 가졌나 — 살아 있는 worker 가 없으면 null(모름) */
    public Map<String, Boolean> liveKeyStatus() {
        var live = list().stream().filter(Worker::live).toList();
        if (live.isEmpty()) return null;
        Map<String, Boolean> out = new java.util.HashMap<>();
        JobCatalog.JOBS.stream().map(JobCatalog.Def::envKey).filter(Objects::nonNull)
                .forEach(k -> out.put(k, live.stream().anyMatch(w -> w.configuredKeys().contains(k))));
        return out;
    }

    /** 죽은 worker(하트비트 끊김)가 잡고 있던 RUNNING 요청만 FAILED — 다른 살아 있는 worker 의 실행은 건드리지 않음 */
    public int abandonOrphans(String reason) {
        return jdbc.sql("""
                UPDATE ops.job_request r SET status = 'FAILED', message = :m, finished_at = now()
                 WHERE r.status = 'RUNNING'
                   AND NOT EXISTS (SELECT 1 FROM ops.worker w WHERE w.worker_id = r.claimed_by
                                      AND w.last_seen_at > now() - make_interval(secs => :live))""")
                .param("m", reason).param("live", LIVE_SECONDS).update();
    }
}
