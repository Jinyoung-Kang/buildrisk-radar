package com.buildrisk.radar.common.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 기동 시 seed 를 DB 와 맞춥니다 (멱등).
 *   std_account · account_map(SEED) · stat_series · universe_override: 파일 기준으로 UPSERT
 *   rule: 규칙 코드가 DB 에 없을 때만 버전 1 로 적재 (이후 변경은 API → 새 버전)
 */
@Component
@Order(0)
public class SeedLoader implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);
    private final SeedCatalog seed;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public SeedLoader(SeedCatalog seed, JdbcClient jdbc, ObjectMapper mapper) {
        this.seed = seed;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int acc = 0, map = 0, series = 0, rules = 0;
        for (Map<String, String> r : seed.csv("std_account.csv")) {
            acc += jdbc.sql("""
                    INSERT INTO ref.std_account (std_code, name_ko, sj_div, flow_type, agg, sort_order)
                    VALUES (:c, :n, :s, :f, :a, :o)
                    ON CONFLICT (std_code) DO UPDATE SET name_ko = EXCLUDED.name_ko, sj_div = EXCLUDED.sj_div,
                      flow_type = EXCLUDED.flow_type, agg = EXCLUDED.agg, sort_order = EXCLUDED.sort_order""")
                    .param("c", r.get("std_code")).param("n", r.get("name_ko")).param("s", r.get("sj_div"))
                    .param("f", r.get("flow_type")).param("a", r.get("agg"))
                    .param("o", Integer.parseInt(r.get("sort_order"))).update();
        }
        for (Map<String, String> r : seed.csv("account_map.csv")) {
            int updated = jdbc.sql("""
                    UPDATE ref.account_map SET priority = :p, abs_value = :abs, note = :note, section = :sec
                    WHERE std_code = :c AND match_type = :t AND pattern = :pat AND sj_div IS NOT DISTINCT FROM :sj""")
                    .param("c", r.get("std_code")).param("sj", r.get("sj_div")).param("t", r.get("match_type"))
                    .param("pat", r.get("pattern")).param("p", Integer.parseInt(r.get("priority")))
                    .param("abs", Boolean.parseBoolean(r.get("abs_value"))).param("note", r.get("note"))
                    .param("sec", r.get("section")).update();
            if (updated == 0) {
                map += jdbc.sql("""
                        INSERT INTO ref.account_map (std_code, sj_div, match_type, pattern, priority, abs_value, origin, note, section)
                        VALUES (:c, :sj, :t, :pat, :p, :abs, 'SEED', :note, :sec)""")
                        .param("c", r.get("std_code")).param("sj", r.get("sj_div")).param("t", r.get("match_type"))
                        .param("pat", r.get("pattern")).param("p", Integer.parseInt(r.get("priority")))
                        .param("abs", Boolean.parseBoolean(r.get("abs_value"))).param("note", r.get("note"))
                        .param("sec", r.get("section")).update();
            }
        }
        // CSV 에서 지운 SEED 규칙은 DB 에서도 지움 (USER 규칙은 유지)
        List<Map<String, String>> csvRules = seed.csv("account_map.csv");
        int removed = 0;
        for (var row : jdbc.sql("SELECT map_id, std_code, sj_div, match_type, pattern FROM ref.account_map WHERE origin = 'SEED'")
                .query().listOfRows()) {
            boolean keep = csvRules.stream().anyMatch(r -> r.get("std_code").equals(row.get("std_code"))
                    && r.get("match_type").equals(row.get("match_type")) && r.get("pattern").equals(row.get("pattern"))
                    && java.util.Objects.equals(r.get("sj_div"), row.get("sj_div")));
            if (!keep) removed += jdbc.sql("DELETE FROM ref.account_map WHERE map_id = :id").param("id", row.get("map_id")).update();
        }
        if (removed > 0) log.info("seed 에서 빠진 매핑 규칙 {}개 삭제", removed);
        series += loadSeries();
        rules += loadRules();
        syncOverrides();
        log.info("seed 동기화: 표준계정 {} · 새 매핑 규칙 {} · 통계 시리즈 {} · 새 규칙 {} ({})", acc, map, series, rules, seed.dir());
    }

    @SuppressWarnings("unchecked")
    private int loadSeries() {
        int n = 0;
        for (Map<String, Object> s : (List<Map<String, Object>>) seed.yaml("stat_series.yml").get("series")) {
            n += jdbc.sql("""
                    INSERT INTO mkt.stat_series (series_id, source, source_table, source_item, stat_code, name_ko, unit, cycle, agg, params)
                    VALUES (:id, :src, :tbl, :itm, :stat, :name, :unit, :cycle, :agg, cast(:params AS jsonb))
                    ON CONFLICT (series_id) DO UPDATE SET source = EXCLUDED.source, source_table = EXCLUDED.source_table,
                      source_item = EXCLUDED.source_item, stat_code = EXCLUDED.stat_code, name_ko = EXCLUDED.name_ko,
                      unit = EXCLUDED.unit, cycle = EXCLUDED.cycle, agg = EXCLUDED.agg, params = EXCLUDED.params""")
                    .param("id", s.get("id")).param("src", s.get("source")).param("tbl", s.get("table"))
                    .param("itm", String.valueOf(s.get("item"))).param("stat", s.get("statCode"))
                    .param("name", s.get("name")).param("unit", s.get("unit")).param("cycle", s.get("cycle"))
                    .param("agg", s.get("agg")).param("params", mapper.writeValueAsString(s.getOrDefault("params", Map.of())))
                    .update();
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private int loadRules() {
        int n = 0;
        for (Map<String, Object> r : (List<Map<String, Object>>) seed.yaml("rules.yml").get("rules")) {
            n += jdbc.sql("""
                    INSERT INTO risk.rule (rule_code, version, target_type, name_ko, description, params, severity, enabled, change_note)
                    SELECT :code, 1, :target, :name, :descr, cast(:params AS jsonb), :sev, true, 'seed/rules.yml 초기값'
                    WHERE NOT EXISTS (SELECT 1 FROM risk.rule WHERE rule_code = :code)""")
                    .param("code", r.get("code")).param("target", r.get("target")).param("name", r.get("name"))
                    .param("descr", r.get("description")).param("sev", r.get("severity"))
                    .param("params", mapper.writeValueAsString(r.get("params"))).update();
        }
        return n;
    }

    private void syncOverrides() {
        jdbc.sql("DELETE FROM ref.universe_override").update();
        seed.universeOverrides().forEach((corp, include) -> jdbc.sql(
                        "INSERT INTO ref.universe_override (corp_code, include, note) VALUES (:c, :i, 'seed/universe_overrides.yml')")
                .param("c", corp).param("i", include).update());
    }
}
