package com.buildrisk.radar.domain.metric;

import com.buildrisk.radar.domain.rule.RuleModels.MetricPoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

@Repository
public class MetricRepository {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper;

    public MetricRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
    }

    public void replaceCompanyMetrics(String corpCode, List<MetricValue> values, UUID calcRunId) {
        jdbc.sql("DELETE FROM risk.company_metric WHERE corp_code = :c").param("c", corpCode).update();
        jdbcTemplate.batchUpdate("""
                INSERT INTO risk.company_metric (corp_code, period_key, metric_code, fs_div, value, status, components, calc_run_id)
                VALUES (?, ?, ?, ?, ?, ?, cast(? AS jsonb), ?)""", values, 500, (ps, v) -> {
            ps.setString(1, v.targetKey());
            ps.setString(2, v.period());
            ps.setString(3, v.metricCode());
            Object fs = v.components().get("fsDiv");
            ps.setString(4, fs == null ? null : String.valueOf(fs));
            ps.setBigDecimal(5, v.value());
            ps.setString(6, v.status());
            ps.setString(7, mapper.writeValueAsString(v.components()));
            ps.setObject(8, calcRunId);
        });
    }

    /**
     * PF 우발채무 지표 (ADR-024) — 기업 · 분기마다 최신 정기보고서의 PF 종합요약표 합계 ÷ 같은 분기 자본총계(연결 우선).
     * companyMetricStep 이 기업 지표를 지우고 다시 쓴 뒤에 실행. 근거 접수번호(rceptNo) = 정기보고서 → point-in-time
     */
    public int insertPfMetrics(UUID calcRunId) {
        // 기업 지표를 다시 계산하지 않은 회사의 옛 PF 값이 남지 않도록 PF 지표는 통째로 지우고 다시 씀 (같은 트랜잭션)
        jdbc.sql("DELETE FROM risk.company_metric WHERE metric_code IN ('PF_GUARANTEE_TO_EQUITY', 'PF_DUE_12M_TO_EQUITY', 'PF_BRIDGE_SHARE')").update();
        return jdbc.sql("""
                INSERT INTO risk.company_metric (corp_code, period_key, metric_code, fs_div, value, status, components, calc_run_id)
                WITH r AS (
                    SELECT DISTINCT ON (corp_code, period_key) * FROM dart.pf_report WHERE status = 'PARSED'
                     ORDER BY corp_code, period_key, rcept_dt DESC, rcept_no DESC),
                e AS (
                    SELECT DISTINCT ON (corp_code, period_key) corp_code, period_key, fs_div, amount, rcept_no
                      FROM dart.fs_std WHERE std_code = 'TOTAL_EQUITY' AND basis = 'POINT'
                     ORDER BY corp_code, period_key, (fs_div = 'CFS') DESC),
                j AS (
                    SELECT r.corp_code, r.period_key, r.rcept_no, r.guarantee, r.bridge_guarantee, r.reconciled, r.stated_total, r.stated_match,
                           -- 12개월 내 만기: 구간이 모두 비면 알 수 없음(null → MISSING), 하나라도 있으면 '-' 는 0 (PfExposureParser.Due.within12m 과 같은 규칙)
                           CASE WHEN num_nonnulls(r.due_3m, r.due_6m, r.due_12m, r.due_2y, r.due_3y, r.due_over3y) = 0 THEN NULL
                                ELSE coalesce(r.due_3m, 0) + coalesce(r.due_6m, 0) + coalesce(r.due_12m, 0) END AS due12,
                           e.fs_div, e.amount AS equity,
                           e.rcept_no AS equity_rcept
                      FROM r LEFT JOIN e USING (corp_code, period_key)),
                m AS (
                    SELECT j.*, 'PF_GUARANTEE_TO_EQUITY' AS code, guarantee AS num, equity AS den, 'PF 보증금액' AS num_label FROM j
                    UNION ALL
                    SELECT j.*, 'PF_DUE_12M_TO_EQUITY', due12, equity, '12개월 내 만기 PF 대출잔액' FROM j
                    UNION ALL
                    -- 브릿지론 행이 없으면 0 (요약표에 PF 보증은 있는데 브릿지론만 없는 경우)
                    SELECT j.*, 'PF_BRIDGE_SHARE', CASE WHEN guarantee IS NULL THEN NULL ELSE coalesce(bridge_guarantee, 0) END,
                           guarantee, '브릿지론 보증금액' FROM j)
                SELECT corp_code, period_key, code, fs_div,
                       CASE WHEN num IS NULL OR den IS NULL OR den <= 0 THEN NULL ELSE round(coalesce(num, 0) * 100 / den, 4) END,
                       CASE WHEN den IS NULL OR num IS NULL THEN 'MISSING'
                            WHEN den <= 0 AND code <> 'PF_BRIDGE_SHARE' THEN 'NEG_EQUITY'
                            WHEN den <= 0 THEN 'ZERO_DENOM' ELSE 'OK' END,
                       jsonb_build_object('rceptNo', rcept_no, 'reprtCode', '-', 'fsDiv', fs_div, 'reconciled', reconciled,
                           'statedTotal', stated_total, 'statedMatch', stated_match,
                           'numerator', jsonb_build_object('label', num_label, 'amount', num, 'source', 'PF 종합요약표(단독 + 컨소시엄 회사분)'),
                           'denominator', jsonb_build_object('label', CASE code WHEN 'PF_BRIDGE_SHARE' THEN 'PF 보증금액' ELSE '자본총계' END,
                               'amount', den, 'rceptNo', CASE code WHEN 'PF_BRIDGE_SHARE' THEN rcept_no ELSE equity_rcept END)),
                       :run
                  FROM m
                ON CONFLICT (corp_code, period_key, metric_code) DO UPDATE SET fs_div = EXCLUDED.fs_div, value = EXCLUDED.value,
                    status = EXCLUDED.status, components = EXCLUDED.components, calc_run_id = EXCLUDED.calc_run_id""")
                .param("run", calcRunId).update();
    }

    public void replaceRegionMetrics(List<MetricValue> values, UUID calcRunId) {
        jdbc.sql("DELETE FROM mkt.region_metric").update();
        jdbcTemplate.batchUpdate("""
                INSERT INTO mkt.region_metric (region_cd, period, metric_code, value, status, components, calc_run_id)
                VALUES (?, ?, ?, ?, ?, cast(? AS jsonb), ?)""", values, 1000, (ps, v) -> {
            ps.setString(1, v.targetKey());
            ps.setString(2, v.period());
            ps.setString(3, v.metricCode());
            ps.setBigDecimal(4, v.value());
            ps.setString(5, v.status());
            ps.setString(6, mapper.writeValueAsString(v.components()));
            ps.setObject(7, calcRunId);
        });
    }

    public NavigableMap<String, MetricPoint> companySeries(String corpCode, String metricCode) {
        return series("SELECT period_key, value, status, components::text FROM risk.company_metric "
                + "WHERE corp_code = :t AND metric_code = :m ORDER BY period_key", corpCode, metricCode);
    }

    public NavigableMap<String, MetricPoint> regionSeries(String regionCd, String metricCode) {
        return series("SELECT period, value, status, components::text FROM mkt.region_metric "
                + "WHERE region_cd = :t AND metric_code = :m ORDER BY period", regionCd, metricCode);
    }

    private NavigableMap<String, MetricPoint> series(String sql, String target, String metric) {
        NavigableMap<String, MetricPoint> out = new TreeMap<>();
        jdbc.sql(sql).param("t", target).param("m", metric).query((rs, i) -> new MetricPoint(rs.getString(1),
                rs.getBigDecimal(2), rs.getString(3), mapper.readValue(rs.getString(4), MAP)))
                .list().forEach(p -> out.put(p.period(), p));
        return out;
    }

    /** 지역 지표 계산 입력: (stat_code, region_cd, period, value) */
    public record StatPoint(String statCode, String agg, String regionCd, String period, BigDecimal value) {}

    public List<StatPoint> allRegionStats() {
        return jdbc.sql("""
                        SELECT s.stat_code, s.agg, r.region_cd, r.period, r.value
                        FROM mkt.region_stat r JOIN mkt.stat_series s ON s.series_id = r.series_id
                        WHERE s.enabled""")
                .query((rs, i) -> new StatPoint(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5))).list();
    }
}
