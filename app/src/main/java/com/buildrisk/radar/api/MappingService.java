package com.buildrisk.radar.api;

import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.domain.universe.CompanyRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** FR-206 · FR-405 — 미매핑 계정·지역 코드와 매핑률, 매핑 규칙 추가 */
@Service
public class MappingService {
    private static final Map<String, String> HINT = Map.ofEntries(
            Map.entry("TOTAL_ASSETS", "자산"), Map.entry("CURRENT_ASSETS", "유동자산"), Map.entry("TOTAL_LIABILITIES", "부채"),
            Map.entry("CURRENT_LIABILITIES", "유동부채"), Map.entry("SHORT_BORROWINGS", "차입|사채"),
            Map.entry("LONG_BORROWINGS", "차입"), Map.entry("BONDS", "사채"), Map.entry("TOTAL_EQUITY", "자본"),
            Map.entry("REVENUE", "매출|수익"), Map.entry("OPERATING_INCOME", "영업"),
            Map.entry("INTEREST_EXPENSE", "이자|금융원가|금융비용"), Map.entry("OPERATING_CASH_FLOW", "영업활동"));
    private final JdbcClient jdbc;
    private final CompanyRepository companies;

    public MappingService(JdbcClient jdbc, CompanyRepository companies) {
        this.jdbc = jdbc;
        this.companies = companies;
    }

    private static final String REPORTS = """
            WITH rep AS (
              SELECT f.corp_code, f.bsns_year, f.reprt_code, f.fs_div,
                     f.bsns_year || 'Q' || CASE f.reprt_code WHEN '11013' THEN 1 WHEN '11012' THEN 2 WHEN '11014' THEN 3 ELSE 4 END AS period_key
              FROM dart.fs_fetch f JOIN ref.company c ON c.corp_code = f.corp_code AND c.is_target
              WHERE f.status = 'OK')
            """;

    public Map<String, Object> unmapped() {
        Map<String, Object> out = new LinkedHashMap<>();
        // 표준계정별 매핑률
        List<Map<String, Object>> byStd = jdbc.sql(REPORTS + """
                SELECT s.std_code, s.name_ko, s.agg, count(*) AS reports,
                       count(x.std_code) AS mapped
                FROM rep CROSS JOIN ref.std_account s
                LEFT JOIN dart.fs_std x ON x.corp_code = rep.corp_code AND x.period_key = rep.period_key
                     AND x.fs_div = rep.fs_div AND x.std_code = s.std_code AND x.basis IN ('POINT', 'CUM')
                GROUP BY s.std_code, s.name_ko, s.agg, s.sort_order ORDER BY s.sort_order""").query().listOfRows();
        long total = 0, mapped = 0;
        for (var r : byStd) {
            long t = ((Number) r.get("reports")).longValue(), m = ((Number) r.get("mapped")).longValue();
            total += t;
            mapped += m;
            r.put("rate", t == 0 ? null : BigDecimal.valueOf(m * 100.0 / t).setScale(1, RoundingMode.HALF_UP));
        }
        out.put("accountMappingRate", total == 0 ? null : BigDecimal.valueOf(mapped * 100.0 / total).setScale(1, RoundingMode.HALF_UP));
        out.put("accountMappingByStd", byStd);
        out.put("accountNote", "사채·장기차입금·이자비용은 회사에 따라 실제로 없거나 주석에만 있어 미매핑이 정상일 수 있습니다 (U-6).");

        // 미매핑 (기업 × 표준계정) + 후보 원천 계정
        List<Map<String, Object>> missing = jdbc.sql(REPORTS + """
                SELECT rep.corp_code, c.corp_name, s.std_code, s.name_ko, s.sj_div,
                       string_agg(rep.period_key, ',' ORDER BY rep.period_key DESC) AS periods,
                       max(rep.bsns_year || rep.reprt_code || rep.fs_div) AS latest
                FROM rep CROSS JOIN ref.std_account s JOIN ref.company c ON c.corp_code = rep.corp_code
                WHERE NOT EXISTS (SELECT 1 FROM dart.fs_std x WHERE x.corp_code = rep.corp_code AND x.period_key = rep.period_key
                                  AND x.fs_div = rep.fs_div AND x.std_code = s.std_code AND x.basis IN ('POINT', 'CUM'))
                GROUP BY rep.corp_code, c.corp_name, s.std_code, s.name_ko, s.sj_div, s.sort_order
                ORDER BY s.sort_order, c.corp_name LIMIT 300""").query().listOfRows();
        for (var m : missing) {
            String latest = String.valueOf(m.get("latest"));
            String sj = String.valueOf(m.get("sj_div"));
            List<String> sjs = sj.equals("IS") ? List.of("IS", "CIS") : List.of(sj);
            m.put("candidates", jdbc.sql("""
                            SELECT DISTINCT ON (account_nm) sj_div, account_id, account_nm, thstrm_amount
                            FROM dart.fs_raw
                            WHERE corp_code = :c AND bsns_year = :y AND reprt_code = :r AND fs_div = :f
                              AND sj_div = ANY(cast(:sj AS text[])) AND account_nm ~ :hint
                            ORDER BY account_nm, rcept_no DESC LIMIT 6""")
                    .param("c", m.get("corp_code")).param("y", latest.substring(0, 4)).param("r", latest.substring(4, 9))
                    .param("f", latest.substring(9)).param("sj", sjs.toArray(String[]::new))
                    .param("hint", HINT.getOrDefault(String.valueOf(m.get("std_code")), ".")).query().listOfRows());
            m.remove("latest");
        }
        out.put("accounts", missing);

        // 지역 코드
        List<Map<String, Object>> bySource = jdbc.sql("""
                SELECT source, count(*) FILTER (WHERE region_cd IS NOT NULL) AS mapped,
                       count(*) FILTER (WHERE match_method = 'UNMAPPED') AS unmapped,
                       count(*) FILTER (WHERE match_method = 'AGGREGATE') AS aggregate
                FROM ref.region_code_map GROUP BY source ORDER BY source""").query().listOfRows();
        for (var r : bySource) {
            long m = ((Number) r.get("mapped")).longValue(), u = ((Number) r.get("unmapped")).longValue();
            r.put("rate", m + u == 0 ? null : BigDecimal.valueOf(m * 100.0 / (m + u)).setScale(1, RoundingMode.HALF_UP));
        }
        out.put("regionMappingBySource", bySource);
        out.put("regions", jdbc.sql("""
                SELECT source, source_code, source_name, match_method, note, updated_at FROM ref.region_code_map
                WHERE match_method = 'UNMAPPED' ORDER BY source, source_name""").query().listOfRows());

        // U-4 유니버스 업종 분포
        Map<String, Object> universe = new LinkedHashMap<>(companies.universeStats());
        universe.put("byInduty", jdbc.sql("""
                SELECT induty_code, count(*) AS n FROM ref.company WHERE is_target GROUP BY induty_code ORDER BY n DESC""")
                .query().listOfRows());
        out.put("universe", universe);
        return out;
    }

    public List<Map<String, Object>> accountRules() {
        return jdbc.sql("""
                SELECT map_id, std_code, sj_div, section, match_type, pattern, priority, abs_value, origin, note, created_at
                FROM ref.account_map ORDER BY std_code, priority, map_id""").query().listOfRows();
    }

    public record AccountRuleRequest(String stdCode, String sjDiv, String matchType, String pattern, Integer priority,
                                     Boolean absValue, String note, String section) {}

    public Map<String, Object> addAccountRule(AccountRuleRequest r) {
        if (r.stdCode() == null || jdbc.sql("SELECT count(*) FROM ref.std_account WHERE std_code = :c")
                .param("c", r.stdCode()).query(Integer.class).single() == 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "알 수 없는 표준계정: " + r.stdCode());
        }
        if (r.matchType() == null || !Set.of("ACCOUNT_ID", "NAME_EXACT", "NAME_REGEX").contains(r.matchType())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "matchType 은 ACCOUNT_ID · NAME_EXACT · NAME_REGEX");
        }
        if (r.pattern() == null || r.pattern().isBlank() || r.pattern().length() > 200) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "pattern 은 1~200자");
        }
        if ("NAME_REGEX".equals(r.matchType())) {
            try {
                Pattern.compile(r.pattern());
            } catch (PatternSyntaxException e) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "정규식 오류: " + e.getDescription());
            }
        }
        if (r.sjDiv() != null && !Set.of("BS", "IS", "CIS", "CF").contains(r.sjDiv())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "sjDiv 는 BS · IS · CIS · CF");
        }
        if (r.section() != null && !Set.of("CURRENT", "NONCURRENT").contains(r.section())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "section 은 CURRENT · NONCURRENT");
        }
        Integer id = jdbc.sql("""
                        INSERT INTO ref.account_map (std_code, sj_div, match_type, pattern, priority, abs_value, origin, note, section)
                        VALUES (:c, :sj, :t, :p, :pr, :abs, 'USER', :note, :sec)
                        ON CONFLICT (std_code, match_type, pattern, sj_div) DO UPDATE SET priority = EXCLUDED.priority
                        RETURNING map_id""")
                .param("c", r.stdCode()).param("sj", r.sjDiv()).param("t", r.matchType()).param("p", r.pattern())
                .param("pr", r.priority() == null ? 30 : r.priority()).param("abs", Boolean.TRUE.equals(r.absValue()))
                .param("note", r.note()).param("sec", r.section()).query(Integer.class).single();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mapId", id);
        out.put("next", "standardizeMetricJob → ruleEvalJob 을 실행하면 반영됩니다 (POST /api/v1/batch/jobs/standardizeMetricJob/launch).");
        return out;
    }

    /** 화면에서 추가한 규칙(origin USER)만 지울 수 있음 — seed 규칙은 seed/account_map.csv 에서 관리 */
    public Map<String, Object> deleteAccountRule(int mapId) {
        String origin = jdbc.sql("SELECT origin FROM ref.account_map WHERE map_id = :id").param("id", mapId)
                .query(String.class).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "매핑 규칙이 없습니다: " + mapId));
        if (!"USER".equals(origin)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "seed 규칙은 seed/account_map.csv 에서 고치세요 (map_id " + mapId + ").");
        }
        jdbc.sql("DELETE FROM ref.account_map WHERE map_id = :id").param("id", mapId).update();
        return Map.of("deleted", mapId, "next", "standardizeMetricJob → ruleEvalJob 을 실행하면 반영됩니다.");
    }

    public record RegionMapRequest(String source, String sourceCode, String regionCd, String note) {}

    public Map<String, Object> mapRegion(RegionMapRequest r) {
        if (r.source() == null || r.sourceCode() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "source · sourceCode 가 필요합니다.");
        }
        String source = r.source(), sourceCode = r.sourceCode();
        if (r.regionCd() != null && jdbc.sql("SELECT count(*) FROM ref.region WHERE region_cd = :c")
                .param("c", r.regionCd()).query(Integer.class).single() == 0) {
            throw ApiException.notFound(ErrorCode.REGION_NOT_FOUND, "지역 " + r.regionCd());
        }
        int n = jdbc.sql("""
                        UPDATE ref.region_code_map SET region_cd = cast(:r AS char(5)),
                          match_method = CASE WHEN cast(:r AS text) IS NULL THEN 'UNMAPPED' ELSE 'MANUAL' END,
                          verified = true, note = :n, updated_at = now()
                        WHERE source = :s AND source_code = :c""")
                .param("r", r.regionCd()).param("n", r.note()).param("s", source).param("c", sourceCode).update();
        if (n == 0) throw new ApiException(ErrorCode.NOT_FOUND, "출처 코드가 없습니다: " + source + "/" + sourceCode);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("sourceCode", sourceCode);
        out.put("regionCd", r.regionCd());
        out.put("next", "해당 출처 수집 Job 과 standardizeMetricJob 을 다시 실행하면 반영됩니다.");
        return out;
    }

    public List<Map<String, Object>> regionCodes(String source) {
        return new ArrayList<>(jdbc.sql("""
                SELECT m.source, m.source_code, m.source_name, m.region_cd, r.full_name AS region_name, m.match_method,
                       m.verified, m.note FROM ref.region_code_map m LEFT JOIN ref.region r ON r.region_cd = m.region_cd
                WHERE cast(:s AS text) IS NULL OR m.source = :s ORDER BY m.source, m.source_name""")
                .param("s", CompanyQueryService.blank(source)).query().listOfRows());
    }
}
