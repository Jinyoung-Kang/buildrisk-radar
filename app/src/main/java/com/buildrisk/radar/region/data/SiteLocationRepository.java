package com.buildrisk.radar.region.data;

import com.buildrisk.radar.region.domain.SiteAddressResolver.Match;
import com.buildrisk.radar.region.domain.SiteAddressResolver.Result;
import com.buildrisk.radar.region.domain.SiteAddressResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 사업장 위치 (ADR-026) — 실거래 법정동 · 지번 사전을 만들고 공급계약 주소를 다시 해석해 저장 (외부 호출 없음) */
@Repository
public class SiteLocationRepository {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public SiteLocationRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    record Target(String rceptNo, String regionCd, String regionText) {}

    /** 받은 실거래 전체에서: 법정동 시군구 → 법정동 → 지번 → 단지 이름 (같은 지번에 이름이 여럿이면 가장 큰 값 하나) */
    Map<String, Map<String, Map<String, String>>> dictionary() {
        Map<String, Map<String, Map<String, String>>> d = new HashMap<>();
        jdbc.sql("""
                SELECT lawd_cd, umd_nm, coalesce(jibun, ''), max(apt_nm) FROM mkt.apt_trade
                 WHERE umd_nm IS NOT NULL AND umd_nm <> '' GROUP BY 1, 2, 3""")
                .query((rs, i) -> {
                    var jib = d.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).computeIfAbsent(rs.getString(2), k -> new HashMap<>());
                    if (!rs.getString(3).isEmpty() && rs.getString(4) != null) jib.put(rs.getString(3), rs.getString(4));
                    return null;
                }).list();
        return d;
    }

    /** 화면 시군구 → 실거래 시군구들 (일반구가 있는 시는 일반구들, 아니면 자기 자신) */
    Map<String, List<String>> lawdUnits() {
        Map<String, List<String>> m = new HashMap<>();
        jdbc.sql("SELECT coalesce(parent_cd, region_cd), region_cd FROM ref.region WHERE NOT synthetic")
                .query((rs, i) -> m.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2))).list();
        return m;
    }

    List<Target> targets() {
        return jdbc.sql("SELECT rcept_no, region_cd, region_text FROM dart.contract WHERE region_match = 'SIGUNGU' AND region_cd IS NOT NULL")
                .query((rs, i) -> new Target(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    }

    /** 모든 시군구 해석 공시를 다시 해석 — 실거래가 새로 들어오면 동 이름 사전이 넓어지므로 매번 전체 */
    @Transactional
    public Map<Match, Integer> relocateAll() {
        var dict = dictionary();
        var units = lawdUnits();
        SiteAddressResolver.Dictionary lookup = lawd -> dict.getOrDefault(lawd, Map.of());
        List<Object[]> rows = new ArrayList<>();
        Map<Match, Integer> counts = new EnumMap<>(Match.class);
        for (Target t : targets()) {
            Result r = SiteAddressResolver.resolve(t.regionText(), units.getOrDefault(t.regionCd(), List.of(t.regionCd())), lookup);
            counts.merge(r.match(), 1, Integer::sum);
            rows.add(new Object[]{r.lawdCd(), r.umd(), r.jibun(), r.aptNm(), r.match().name(), SiteAddressResolver.VERSION, t.rceptNo()});
        }
        jdbcTemplate.batchUpdate("""
                UPDATE dart.contract SET site_lawd_cd = ?, site_umd = ?, site_jibun = ?, site_apt = ?, site_match = ?, site_version = ?,
                       site_resolved_at = now() WHERE rcept_no = ?""", rows);
        return counts;
    }
}
