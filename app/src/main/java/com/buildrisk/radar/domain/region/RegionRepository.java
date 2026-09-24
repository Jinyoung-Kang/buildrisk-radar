package com.buildrisk.radar.domain.region;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RegionRepository {
    private static final Logger log = LoggerFactory.getLogger(RegionRepository.class);
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public RegionRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** V-World 시군구 한 행. level 3 = '수원시 장안구' 처럼 이름에 공백이 있는 일반구 */
    public record BoundaryRow(String regionCd, String name, String fullName, String sidoName, int level, String geometryJson,
                              int srid, String source) {}

    public void upsertBoundaries(List<? extends BoundaryRow> rows) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level, synthetic, geom, source, loaded_at)
                VALUES (?, ?, ?, ?, ?, ?, false,
                        ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(?), ?), 4326)), 3)), ?, now())
                ON CONFLICT (region_cd) DO UPDATE SET name = EXCLUDED.name, full_name = EXCLUDED.full_name,
                  sido_cd = EXCLUDED.sido_cd, sido_name = EXCLUDED.sido_name, level = EXCLUDED.level,
                  synthetic = false, geom = EXCLUDED.geom, source = EXCLUDED.source, loaded_at = now()""", rows, 50, (ps, r) -> {
            ps.setString(1, r.regionCd());
            ps.setString(2, r.name());
            ps.setString(3, r.fullName());
            ps.setString(4, r.regionCd().substring(0, 2));
            ps.setString(5, r.sidoName());
            ps.setInt(6, r.level());
            ps.setString(7, r.geometryJson());
            ps.setInt(8, r.srid());
            ps.setString(9, r.source());
        });
    }

    /** 경계 출처가 바뀌면(예: SGIS → V-World) 기준 코드 체계가 달라지므로 지역 파생 데이터를 비우고 다시 적재합니다. */
    public boolean purgeIfSourceChanged(String source) {
        List<String> existing = jdbc.sql("SELECT DISTINCT source FROM ref.region").query(String.class).list();
        if (existing.isEmpty() || (existing.size() == 1 && existing.get(0).equals(source))) return false;
        log.warn("경계 출처 변경 {} → {}: 지역 통계·지표·매핑·지역 경보를 비우고 다시 적재합니다.", existing, source);
        jdbc.sql("DELETE FROM risk.alert WHERE target_type = 'REGION'").update();
        jdbc.sql("DELETE FROM mkt.region_metric").update();
        jdbc.sql("DELETE FROM mkt.region_stat").update();
        jdbc.sql("DELETE FROM ref.region_code_map").update();
        jdbc.sql("UPDATE ref.region SET parent_cd = NULL").update();
        jdbc.sql("DELETE FROM ref.region").update();
        return true;
    }

    /**
     * 일반구(level 3)가 있는 시는 경계를 합쳐 화면 단위(level 2, synthetic) 행을 만들고 부모로 연결합니다.
     * 그다음 화면 단위 경계를 커버리지 단순화(인접 경계 공유 유지, 약 100m)하고 대표점을 계산합니다.
     */
    public int synthesizeParentsAndSimplify() {
        int parents = jdbc.sql("""
                INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level, synthetic, geom, source, loaded_at)
                SELECT substr(c.region_cd, 1, 4) || '0', split_part(c.name, ' ', 1),
                       c.sido_name || ' ' || split_part(c.name, ' ', 1), c.sido_cd, c.sido_name, 2, true,
                       ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_Union(c.geom)), 3)), min(c.source), now()
                FROM ref.region c WHERE c.level = 3
                GROUP BY substr(c.region_cd, 1, 4), split_part(c.name, ' ', 1), c.sido_cd, c.sido_name
                ON CONFLICT (region_cd) DO UPDATE SET geom = EXCLUDED.geom, name = EXCLUDED.name,
                  full_name = EXCLUDED.full_name, loaded_at = now()
                WHERE ref.region.synthetic""").update();
        jdbc.sql("UPDATE ref.region SET parent_cd = substr(region_cd, 1, 4) || '0' WHERE level = 3").update();
        if (geosAtLeast(3, 12)) {
            // 인접 시군구가 경계를 공유한 채로 단순화 (틈·겹침 없음)
            jdbc.sql("""
                    UPDATE ref.region r SET geom_s = s.g FROM (
                      SELECT region_cd, ST_Multi(ST_CollectionExtract(ST_CoverageSimplify(geom, 0.0009) OVER (), 3)) AS g
                      FROM ref.region WHERE level = 2) s
                    WHERE r.region_cd = s.region_cd""").update();
        } else {
            log.info("GEOS 3.12 미만 — ST_CoverageSimplify 대신 개별 ST_SimplifyPreserveTopology 사용");
            jdbc.sql("UPDATE ref.region SET geom_s = ST_Multi(ST_SimplifyPreserveTopology(geom, 0.0009)) WHERE level = 2").update();
        }
        jdbc.sql("UPDATE ref.region SET geom_s = geom WHERE geom_s IS NULL OR ST_IsEmpty(geom_s)").update();
        jdbc.sql("UPDATE ref.region SET centroid = ST_PointOnSurface(geom) WHERE geom IS NOT NULL").update();
        return parents;
    }

    private boolean geosAtLeast(int major, int minor) {
        String v = jdbc.sql("SELECT postgis_geos_version()").query(String.class).single();   // 예: 3.11.1-CAPI-1.17.1
        String[] p = v.split("[.-]");
        int ma = Integer.parseInt(p[0]), mi = Integer.parseInt(p[1]);
        return ma > major || (ma == major && mi >= minor);
    }

    public List<RegionRef> refs() {
        return jdbc.sql("SELECT region_cd, name, sido_name, level, parent_cd FROM ref.region ORDER BY region_cd")
                .query((rs, i) -> new RegionRef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5))).list();
    }

    public Optional<String> manualMapping(String source, String sourceCode) {
        return jdbc.sql("""
                        SELECT region_cd FROM ref.region_code_map
                        WHERE source = :s AND source_code = :c AND match_method = 'MANUAL' AND region_cd IS NOT NULL""")
                .param("s", source).param("c", sourceCode).query(String.class).optional();
    }

    public void upsertCodeMap(String source, String sourceCode, String sourceName, String regionCd, String method, String note) {
        jdbc.sql("""
                INSERT INTO ref.region_code_map (source, source_code, source_name, region_cd, match_method, note)
                VALUES (:s, :c, :n, :r, :m, :note)
                ON CONFLICT (source, source_code) DO UPDATE SET source_name = EXCLUDED.source_name,
                  region_cd = EXCLUDED.region_cd, match_method = EXCLUDED.match_method, note = EXCLUDED.note, updated_at = now()
                WHERE ref.region_code_map.match_method <> 'MANUAL' OR ref.region_code_map.region_cd IS NULL""")
                .param("s", source).param("c", sourceCode).param("n", sourceName).param("r", regionCd)
                .param("m", method).param("note", note).update();
    }

    public record StatRow(String seriesId, String regionCd, String period, BigDecimal value, String rawSymbol,
                          String sourceCode, UUID collectRunId) {}

    public void upsertStats(List<? extends StatRow> rows) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO mkt.region_stat (series_id, region_cd, period, value, raw_symbol, source_code, collect_run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (series_id, region_cd, period) DO UPDATE SET value = EXCLUDED.value,
                  raw_symbol = EXCLUDED.raw_symbol, source_code = EXCLUDED.source_code,
                  collect_run_id = EXCLUDED.collect_run_id, fetched_at = now()""", rows, 500, (ps, r) -> {
            ps.setString(1, r.seriesId());
            ps.setString(2, r.regionCd());
            ps.setString(3, r.period());
            ps.setBigDecimal(4, r.value());
            ps.setString(5, r.rawSymbol());
            ps.setString(6, r.sourceCode());
            ps.setObject(7, r.collectRunId());
        });
    }

    public record SeriesDef(String seriesId, String source, String table, String item, String statCode, String agg,
                            String paramsJson) {}

    public List<SeriesDef> series(String source) {
        return jdbc.sql("""
                        SELECT series_id, source, source_table, source_item, stat_code, agg, params::text
                        FROM mkt.stat_series WHERE enabled AND source = :s ORDER BY series_id""")
                .param("s", source)
                .query((rs, i) -> new SeriesDef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7))).list();
    }

    public int count() { return jdbc.sql("SELECT count(*) FROM ref.region").query(Integer.class).single(); }
}
