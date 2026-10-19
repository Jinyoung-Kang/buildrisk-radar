package com.buildrisk.radar.metric.data;

import com.buildrisk.radar.metric.domain.MetricPoint;
import com.buildrisk.radar.metric.domain.MetricValue;
import com.buildrisk.radar.metric.domain.StatPoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

@Repository
public class MetricRepository {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    /** 지역별 최신 '천 가구당 미분양' (위험 지역 판단 기준) */
    public static final String LATEST_UNSOLD = """
            SELECT DISTINCT ON (region_cd) region_cd, value, period FROM mkt.region_metric
             WHERE metric_code = 'UNSOLD_PER_1K_HH' AND status = 'OK' ORDER BY region_cd, period DESC""";
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper;

    public MetricRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
    }

    /** PF 지표 — 기업 지표와 따로 pfMetricStep 이 한 트랜잭션으로 바꿈 */
    static final List<String> PF_CODES = List.of("PF_GUARANTEE_TO_EQUITY", "PF_DUE_12M_TO_EQUITY", "PF_BRIDGE_SHARE");

    /**
     * 기업 하나의 재무 지표를 바꿈 — PF 지표는 건드리지 않음. 전에는 PF 까지 지우고 다음 Step 에서야 다시 넣어, 그 사이(또는 다음 Step 이
     * 실패 · 멈추면 다음 실행까지) 화면과 규칙 평가에서 PF 지표가 비었음 (VERIFICATION 126)
     */
    public void replaceCompanyMetrics(String corpCode, List<MetricValue> values, UUID calcRunId) {
        jdbc.sql("DELETE FROM risk.company_metric WHERE corp_code = :c AND metric_code NOT IN (:pf)")
                .param("c", corpCode).param("pf", PF_CODES).update();
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
        jdbc.sql("DELETE FROM risk.company_metric WHERE metric_code IN (:pf)").param("pf", PF_CODES).update();
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
                           -- 12개월 내 만기 = 파서가 원문 구간에서 셈(PfExposureParser.Due.within12m) — 모르면 null → MISSING.
                           -- 표준 여섯 칸으로 나뉘지 않는 원문 구간('6개월이내')도 합은 앎 (V24)
                           r.due_within_12m AS due12,
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
                       -- 재무 지표(CompanyMetricCalculator.ratio)와 같은 분류: 분모 0 은 ZERO_DENOM, 자본이 음수면 NEG_EQUITY (VERIFICATION 189)
                       CASE WHEN den IS NULL OR num IS NULL THEN 'MISSING'
                            WHEN den = 0 THEN 'ZERO_DENOM'
                            WHEN den < 0 AND code <> 'PF_BRIDGE_SHARE' THEN 'NEG_EQUITY'
                            WHEN den < 0 THEN 'ZERO_DENOM' ELSE 'OK' END,
                       jsonb_build_object('rceptNo', rcept_no, 'reprtCode', '-', 'fsDiv', fs_div, 'reconciled', reconciled,
                           'statedTotal', stated_total, 'statedMatch', stated_match,
                           'numerator', jsonb_build_object('label', num_label, 'amount', num, 'source', 'PF 종합요약표(단독 + 컨소시엄 회사분)'),
                           'denominator', jsonb_build_object('label', CASE code WHEN 'PF_BRIDGE_SHARE' THEN 'PF 보증금액' ELSE '자본총계' END,
                               'amount', den, 'rceptNo', CASE code WHEN 'PF_BRIDGE_SHARE' THEN rcept_no ELSE equity_rcept END,
                               -- 근거 계보(ADR-027): 자본총계는 fs_std 의 시점값
                               'std', CASE code WHEN 'PF_BRIDGE_SHARE' THEN NULL ELSE 'TOTAL_EQUITY' END,
                               'basis', CASE code WHEN 'PF_BRIDGE_SHARE' THEN NULL ELSE 'POINT' END)),
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

    public List<StatPoint> allRegionStats() {
        return jdbc.sql("""
                        SELECT s.stat_code, s.agg, r.region_cd, r.period, r.value
                        FROM mkt.region_stat r JOIN mkt.stat_series s ON s.series_id = r.series_id
                        WHERE s.enabled""")
                .query((rs, i) -> new StatPoint(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5))).list();
    }
}
