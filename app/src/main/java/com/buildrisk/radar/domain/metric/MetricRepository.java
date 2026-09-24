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
