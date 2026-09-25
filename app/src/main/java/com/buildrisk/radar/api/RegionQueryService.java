package com.buildrisk.radar.api;

import com.buildrisk.radar.api.dto.RegionDtos.AlertBrief;
import com.buildrisk.radar.api.dto.RegionDtos.MetricPoint;
import com.buildrisk.radar.api.dto.RegionDtos.MetricSeries;
import com.buildrisk.radar.api.dto.RegionDtos.Point;
import com.buildrisk.radar.api.dto.RegionDtos.RegionList;
import com.buildrisk.radar.api.dto.RegionDtos.RegionRow;
import com.buildrisk.radar.api.dto.RegionDtos.RegionSeries;
import com.buildrisk.radar.api.dto.RegionDtos.StatSeries;
import com.buildrisk.radar.common.Disclaimer;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.domain.metric.MetricCatalog;
import com.buildrisk.radar.domain.metric.MetricRepository.StatPoint;
import com.buildrisk.radar.domain.metric.RegionSeriesAssembler;
import com.buildrisk.radar.domain.region.RegionRef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

@Service
public class RegionQueryService {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public RegionQueryService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<String> periods(String metric) {
        return jdbc.sql("SELECT DISTINCT period FROM mkt.region_metric WHERE metric_code = :m AND value IS NOT NULL ORDER BY period DESC")
                .param("m", metric).query(String.class).list();
    }

    private String resolvePeriod(String metric, String period, List<String> periods) {
        if (!MetricCatalog.exists(metric) || !"REGION".equals(MetricCatalog.get(metric).target())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "지역 지표 코드가 아닙니다: " + metric);
        }
        if (period != null && !period.isBlank()) return period;
        return periods.isEmpty() ? null : periods.get(0);
    }

    public RegionList list(String sido, String metric, String period) {
        String m = metric == null ? "UNSOLD_PER_1K_HH" : metric;
        List<String> periods = periods(m);
        String p = resolvePeriod(m, period, periods);
        List<RegionRow> rows = jdbc.sql("""
                        SELECT r.region_cd, r.name, r.full_name, r.sido_cd, r.sido_name, x.value, x.status, coalesce(a.cnt, 0)
                        FROM ref.region r
                        LEFT JOIN mkt.region_metric x ON x.region_cd = r.region_cd AND x.metric_code = :m AND x.period = :p
                        LEFT JOIN (SELECT target_key, count(*) AS cnt FROM risk.alert
                                   WHERE target_type = 'REGION' AND status IN ('OPEN', 'ACK') GROUP BY target_key) a
                               ON a.target_key = r.region_cd
                        WHERE r.level = 2 AND (cast(:s AS text) IS NULL OR r.sido_cd = :s)
                        ORDER BY x.value DESC NULLS LAST, r.region_cd""")
                .param("m", m).param("p", p).param("s", CompanyQueryService.blank(sido))
                .query((rs, i) -> new RegionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBigDecimal(6), rs.getString(7), rs.getInt(8))).list();
        String run = jdbc.sql("SELECT calc_run_id::text FROM mkt.region_metric LIMIT 1").query(String.class).optional().orElse(null);
        return new RegionList(m, MetricCatalog.get(m).unit(), p, periods, rows, run, Disclaimer.TEXT);
    }

    /** 단계구분도 GeoJSON — 기본 단순화 경계(geom_s, 약 100m) 또는 요청한 허용오차로 즉석 단순화 */
    public String geojson(String metric, String period, String sido, int simplify) {
        String m = metric == null ? "UNSOLD_PER_1K_HH" : metric;
        String p = resolvePeriod(m, period, periods(m));
        String geomExpr = simplify == 100 ? "coalesce(r.geom_s, r.geom)"
                : simplify <= 0 ? "r.geom" : "ST_SimplifyPreserveTopology(r.geom, " + (simplify / 111_320.0) + ")";
        String features = jdbc.sql("""
                        SELECT coalesce(json_agg(json_build_object(
                                 'type', 'Feature',
                                 'properties', json_build_object('regionCd', r.region_cd, 'name', r.name,
                                     'fullName', r.full_name, 'sidoCd', r.sido_cd, 'sidoName', r.sido_name,
                                     'value', x.value, 'status', coalesce(x.status, 'MISSING'), 'alertCount', coalesce(a.cnt, 0),
                                     'lat', ST_Y(r.centroid), 'lon', ST_X(r.centroid)),
                                 'geometry', ST_AsGeoJSON(""" + geomExpr + """
                        , 5)::json) ORDER BY r.region_cd), '[]'::json)::text
                        FROM ref.region r
                        LEFT JOIN mkt.region_metric x ON x.region_cd = r.region_cd AND x.metric_code = :m AND x.period = :p
                        LEFT JOIN (SELECT target_key, count(*) AS cnt FROM risk.alert
                                   WHERE target_type = 'REGION' AND status IN ('OPEN', 'ACK') GROUP BY target_key) a
                               ON a.target_key = r.region_cd
                        WHERE r.level = 2 AND r.geom IS NOT NULL AND (cast(:s AS text) IS NULL OR r.sido_cd = :s)""")
                .param("m", m).param("p", p).param("s", CompanyQueryService.blank(sido)).query(String.class).single();
        var def = MetricCatalog.get(m);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("metric", m);
        meta.put("nameKo", def.nameKo());
        meta.put("unit", def.unit());
        meta.put("period", p);
        meta.put("calcRunId", jdbc.sql("SELECT calc_run_id::text FROM mkt.region_metric LIMIT 1").query(String.class).optional().orElse(null));
        meta.put("sources", List.of(def.source(), "V-World 시군구 경계"));
        meta.put("crs", "EPSG:4326");
        meta.put("simplifyMeters", simplify);
        meta.put("disclaimer", Disclaimer.TEXT);
        return "{\"meta\":" + mapper.writeValueAsString(meta) + ",\"type\":\"FeatureCollection\",\"features\":" + features + "}";
    }

    public RegionSeries series(String regionCd, List<String> stats) {
        record R(String cd, String name, String full, String sido, int level, String parent) {}
        R r = jdbc.sql("SELECT region_cd, name, full_name, sido_name, level, parent_cd FROM ref.region WHERE region_cd = :c")
                .param("c", regionCd).query((rs, i) -> new R(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getString(6)))
                .optional().orElseThrow(() -> ApiException.notFound(ErrorCode.REGION_NOT_FOUND, "지역 " + regionCd));
        String display = r.level() == 3 && r.parent() != null ? r.parent() : r.cd();
        List<RegionRef> refs = jdbc.sql("""
                        SELECT region_cd, name, sido_name, level, parent_cd FROM ref.region
                        WHERE region_cd = :d OR parent_cd = :d""").param("d", display)
                .query((rs, i) -> new RegionRef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5))).list();
        List<StatPoint> pts = jdbc.sql("""
                        SELECT s.stat_code, s.agg, x.region_cd, x.period, x.value
                        FROM mkt.region_stat x JOIN mkt.stat_series s ON s.series_id = x.series_id
                        WHERE x.region_cd IN (SELECT region_cd FROM ref.region WHERE region_cd = :d OR parent_cd = :d)""")
                .param("d", display).query((rs, i) -> new StatPoint(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getBigDecimal(5))).list();
        var assembled = RegionSeriesAssembler.assemble(refs, pts).get(display);
        List<StatSeries> statSeries = new ArrayList<>();
        if (assembled != null) {
            add(statSeries, stats, "UNSOLD", "미분양 주택", "호", "KOSIS 116/DT_MLTM_2082", assembled.unsold());
            add(statSeries, stats, "SALE_IDX", "아파트 매매가격지수", "지수", "R-ONE A_2024_00045", assembled.sale());
            add(statSeries, stats, "JEONSE_IDX", "아파트 전세가격지수", "지수", "R-ONE A_2024_00050", assembled.jeonse());
            add(statSeries, stats, "HOUSEHOLDS", "총가구", "가구", "SGIS 총조사 주요지표", assembled.households());
            add(statSeries, stats, "TRADE_CNT", "아파트 매매 거래", "건", "국토부 실거래 RTMSDataSvcAptTrade", assembled.trades());
            add(statSeries, stats, "CANCEL_CNT", "아파트 매매 계약 해제", "건", "국토부 실거래 RTMSDataSvcAptTrade", assembled.cancels());
            add(statSeries, stats, "PRICE_M2", "㎡당 중위 매매가", "만원/㎡", "국토부 실거래 RTMSDataSvcAptTrade", assembled.priceM2());
        }
        record M(String code, String p, BigDecimal v, String st, String comp) {}
        List<M> ms = jdbc.sql("""
                        SELECT metric_code, period, value, status, components::text FROM mkt.region_metric
                        WHERE region_cd = :d ORDER BY period""").param("d", display)
                .query((rs, i) -> new M(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4),
                        rs.getString(5))).list();
        List<MetricSeries> metrics = new ArrayList<>();
        for (var d : MetricCatalog.of("REGION")) {
            metrics.add(new MetricSeries(d.code(), d.nameKo(), d.unit(), d.formula(), ms.stream()
                    .filter(x -> x.code().equals(d.code()))
                    .map(x -> new MetricPoint(x.p(), x.v(), x.st(), mapper.readValue(x.comp(), MAP))).toList()));
        }
        List<AlertBrief> alerts = jdbc.sql("""
                        SELECT alert_id, rule_code, severity, as_of, title, status FROM risk.alert
                        WHERE target_type = 'REGION' AND target_key = :d ORDER BY as_of DESC, alert_id DESC LIMIT 20""")
                .param("d", display).query((rs, i) -> new AlertBrief(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6))).list();
        List<Map<String, Object>> codes = jdbc.sql("""
                        SELECT source, source_code, source_name, match_method, region_cd FROM ref.region_code_map
                        WHERE region_cd IN (SELECT region_cd FROM ref.region WHERE region_cd = :d OR parent_cd = :d)
                        ORDER BY source, source_name""").param("d", display).query().listOfRows();
        List<String> children = refs.stream().filter(x -> x.level() == 3).map(RegionRef::name).toList();
        R d = display.equals(r.cd()) ? r : jdbc.sql("SELECT region_cd, name, full_name, sido_name, level, parent_cd FROM ref.region WHERE region_cd = :c")
                .param("c", display).query((rs, i) -> new R(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getString(6))).single();
        return new RegionSeries(d.cd(), d.name(), d.full(), d.sido(), children, statSeries, metrics, alerts, codes,
                Disclaimer.TEXT);
    }

    private static void add(List<StatSeries> out, List<String> filter, String code, String name, String unit, String src,
                            NavigableMap<String, BigDecimal> s) {
        if (filter != null && !filter.isEmpty() && !filter.contains(code)) return;
        out.add(new StatSeries(code, name, unit, src, s.entrySet().stream()
                .map(e -> new Point(e.getKey(), e.getValue())).toList()));
    }
}
