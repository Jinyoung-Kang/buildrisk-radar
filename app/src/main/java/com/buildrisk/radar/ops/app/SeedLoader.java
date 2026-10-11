package com.buildrisk.radar.ops.app;

import com.buildrisk.radar.adapters.seed.SeedCatalog;
import com.buildrisk.radar.alert.data.RuleRepository;
import com.buildrisk.radar.company.data.CompanyRepository;
import com.buildrisk.radar.company.data.FsRepository;
import com.buildrisk.radar.region.data.RegionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 기동 시 seed 를 DB 와 맞춥니다 (멱등).
 *   std_account · account_map(SEED) · stat_series · universe_override: 파일 기준으로 UPSERT
 *   rule: 규칙 코드가 DB 에 없을 때만 버전 1 로 적재 (이후 변경은 API → 새 버전)
 * 파일은 SeedCatalog(adapters)가 읽고, 쓰기는 각 기능의 저장소가 함 — 예전에는 여기서 기능 테이블 5개에 직접 써서 같은 테이블의 쓰기가
 * 두 곳에서 달리 동작했음 (ADR-029, VERIFICATION 191)
 */
@Component
@Order(0)
public class SeedLoader implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);
    private final SeedCatalog seed;
    private final FsRepository fs;
    private final CompanyRepository companies;
    private final RegionRepository regions;
    private final RuleRepository rules;
    private final ObjectMapper mapper;

    public SeedLoader(SeedCatalog seed, FsRepository fs, CompanyRepository companies, RegionRepository regions, RuleRepository rules,
                      ObjectMapper mapper) {
        this.seed = seed;
        this.fs = fs;
        this.companies = companies;
        this.regions = regions;
        this.rules = rules;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int acc = 0, map = 0, series = 0, newRules = 0;
        for (Map<String, String> r : seed.csv("std_account.csv")) {
            acc += fs.upsertStdAccount(r.get("std_code"), r.get("name_ko"), r.get("sj_div"), r.get("flow_type"), r.get("agg"),
                    Integer.parseInt(r.get("sort_order")));
        }
        List<Map<String, String>> csvRules = seed.csv("account_map.csv");
        for (Map<String, String> r : csvRules) {
            map += fs.syncSeedMapRule(r.get("std_code"), r.get("sj_div"), r.get("match_type"), r.get("pattern"),
                    Integer.parseInt(r.get("priority")), Boolean.parseBoolean(r.get("abs_value")), r.get("note"), r.get("section"));
        }
        // CSV 에서 지운 SEED 규칙은 DB 에서도 지움 (USER 규칙은 유지)
        int removed = 0;
        for (var row : fs.seedMapRules()) {
            boolean keep = csvRules.stream().anyMatch(r -> r.get("std_code").equals(row.stdCode())
                    && r.get("match_type").equals(row.matchType()) && r.get("pattern").equals(row.pattern())
                    && Objects.equals(r.get("sj_div"), row.sjDiv()));
            if (!keep) {
                fs.deleteMapRule(row.mapId());
                removed++;
            }
        }
        if (removed > 0) log.info("seed 에서 빠진 매핑 규칙 {}개 삭제", removed);
        series += loadSeries();
        newRules += loadRules();
        companies.replaceUniverseOverrides(seed.universeOverrides());
        log.info("seed 동기화: 표준계정 {} · 새 매핑 규칙 {} · 통계 시리즈 {} · 새 규칙 {} ({})", acc, map, series, newRules, seed.dir());
    }

    @SuppressWarnings("unchecked")
    private int loadSeries() {
        int n = 0;
        for (Map<String, Object> s : (List<Map<String, Object>>) seed.yaml("stat_series.yml").get("series")) {
            n += regions.upsertStatSeries((String) s.get("id"), (String) s.get("source"), (String) s.get("table"),
                    String.valueOf(s.get("item")), (String) s.get("statCode"), (String) s.get("name"), (String) s.get("unit"),
                    (String) s.get("cycle"), (String) s.get("agg"), mapper.writeValueAsString(s.getOrDefault("params", Map.of())));
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private int loadRules() {
        int n = 0;
        for (Map<String, Object> r : (List<Map<String, Object>>) seed.yaml("rules.yml").get("rules")) {
            n += rules.insertSeedRuleIfMissing((String) r.get("code"), (String) r.get("target"), (String) r.get("name"),
                    (String) r.get("description"), mapper.writeValueAsString(r.get("params")), (String) r.get("severity"));
        }
        return n;
    }
}
