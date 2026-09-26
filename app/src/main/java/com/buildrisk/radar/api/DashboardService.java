package com.buildrisk.radar.api;

import com.buildrisk.radar.batch.support.JobCatalog;
import com.buildrisk.radar.common.Disclaimer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** 대시보드 (10장 /) — 열린 경보 수, 최근 경보, 미분양 상위 지역, 최근 배치, 데이터 기준 시점 */
@Service
public class DashboardService {
    private final JdbcClient jdbc;

    public DashboardService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("openAlerts", jdbc.sql("""
                SELECT count(*) FILTER (WHERE severity = 'HIGH') AS "HIGH", count(*) FILTER (WHERE severity = 'MEDIUM') AS "MEDIUM",
                       count(*) FILTER (WHERE severity = 'LOW') AS "LOW",
                       count(*) FILTER (WHERE target_type = 'COMPANY') AS "company", count(*) FILTER (WHERE target_type = 'REGION') AS "region",
                       count(*) FILTER (WHERE status = 'ACK') AS "acked", count(*) AS "total"
                FROM risk.alert WHERE status IN ('OPEN', 'ACK')""").query().singleRow());
        out.put("recentAlerts", jdbc.sql("""
                SELECT a.alert_id AS "alertId", a.rule_code AS "ruleCode", a.severity, a.target_type AS "targetType",
                       a.target_key AS "targetKey",
                       CASE a.target_type WHEN 'COMPANY' THEN (SELECT corp_name FROM ref.company c WHERE c.corp_code = a.target_key)
                            ELSE (SELECT full_name FROM ref.region r WHERE r.region_cd = a.target_key) END AS "targetName",
                       a.as_of AS "asOf", a.title, a.status, a.first_seen_at AS "firstSeenAt"
                FROM risk.alert a WHERE a.status IN ('OPEN', 'ACK')
                ORDER BY CASE a.severity WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, a.as_of DESC, a.alert_id DESC LIMIT 10""")
                .query().listOfRows());
        String p = jdbc.sql("SELECT max(period) FROM mkt.region_metric WHERE metric_code = 'UNSOLD_PER_1K_HH' AND value IS NOT NULL")
                .query(String.class).optional().orElse(null);
        out.put("unsoldPeriod", p);
        out.put("topUnsold", jdbc.sql("""
                SELECT r.region_cd AS "regionCd", r.full_name AS "name", m.value AS "per1kHh", u.value AS "units", c.value AS "chg3m"
                FROM mkt.region_metric m JOIN ref.region r ON r.region_cd = m.region_cd
                LEFT JOIN mkt.region_metric u ON u.region_cd = m.region_cd AND u.period = m.period AND u.metric_code = 'UNSOLD_UNITS'
                LEFT JOIN mkt.region_metric c ON c.region_cd = m.region_cd AND c.period = m.period AND c.metric_code = 'UNSOLD_3M_CHG'
                WHERE m.metric_code = 'UNSOLD_PER_1K_HH' AND m.period = :p AND m.value IS NOT NULL
                ORDER BY m.value DESC LIMIT 10""").param("p", p).query().listOfRows());
        out.put("riskyCompanies", jdbc.sql("""
                SELECT c.corp_code AS "corpCode", c.corp_name AS "corpName", count(*) AS "openAlerts",
                       max(CASE a.severity WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END) AS "sevRank"
                FROM risk.alert a JOIN ref.company c ON c.corp_code = a.target_key
                WHERE a.target_type = 'COMPANY' AND a.status IN ('OPEN', 'ACK')
                GROUP BY c.corp_code, c.corp_name ORDER BY "sevRank" DESC, "openAlerts" DESC, c.corp_name LIMIT 8""")
                .query().listOfRows());
        out.put("batch", jdbc.sql("""
                SELECT DISTINCT ON (i.job_name) i.job_name AS "jobName", e.job_execution_id AS "jobExecutionId", e.status,
                       e.start_time AS "startTime", e.end_time AS "endTime"
                FROM ops.batch_job_execution e JOIN ops.batch_job_instance i ON i.job_instance_id = e.job_instance_id
                ORDER BY i.job_name, e.job_execution_id DESC""").query().listOfRows());
        out.put("jobOrder", JobCatalog.JOBS.stream().map(JobCatalog.Def::name).toList());
        // 화면 이름도 카탈로그 한 곳에서 — Job 이 늘어도 화면이 코드명을 그대로 보이지 않게
        Map<String, String> titles = new LinkedHashMap<>();
        JobCatalog.JOBS.forEach(d -> titles.put(d.name(), d.title()));
        out.put("jobTitles", titles);
        out.put("freshness", jdbc.sql("""
                SELECT (SELECT count(*) FROM ref.company WHERE is_target) AS "universe",
                       (SELECT max(period_key) FROM risk.company_metric WHERE value IS NOT NULL) AS "latestFsPeriod",
                       (SELECT max(rcept_dt)::text FROM dart.disclosure) AS "latestDisclosure",
                       (SELECT max(period) FROM mkt.region_metric WHERE metric_code = 'UNSOLD_UNITS' AND value IS NOT NULL) AS "latestUnsold",
                       (SELECT max(period) FROM mkt.region_metric WHERE metric_code = 'PRICE_IDX_3M_CHG' AND value IS NOT NULL) AS "latestPrice",
                       (SELECT count(*) FROM ref.region WHERE level = 2) AS "regions",
                       (SELECT max(finished_at) FROM ops.calc_run) AS "lastCalcAt" """).query().singleRow());
        out.put("disclaimer", Disclaimer.TEXT);
        return out;
    }
}
