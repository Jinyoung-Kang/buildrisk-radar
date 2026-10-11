package com.buildrisk.radar.ops.api;

import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 매핑 화면 API — 표준계정별 매핑률과 미매핑 (기업 × 표준계정)의 원천 계정 후보 (FR-206) */
@AutoConfigureMockMvc
class MappingIT extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ref.account_map WHERE origin = 'USER' AND pattern = '자산합계(시험)'");   // 중간에 실패해도 표준화 규칙에 남지 않게
    }

    private void raw(String corp, String sj, int line, String id, String name, long amount) {
        jdbc.update("""
                INSERT INTO dart.fs_raw (corp_code, bsns_year, reprt_code, fs_div, rcept_no, sj_div, line_no, account_id, account_nm, thstrm_amount)
                VALUES (?, '2025', '11011', 'CFS', '20260301000001', ?, ?, ?, ?, ?)""", corp, sj, line, id, name, amount);
    }

    private JsonNode account(JsonNode accounts, String std) {
        for (JsonNode a : accounts) if (a.get("std_code").asString().equals(std)) return a;
        return null;
    }

    private List<String> names(JsonNode candidates) {
        List<String> out = new ArrayList<>();
        candidates.forEach(c -> out.add(c.get("sj_div").asString() + ":" + c.get("account_nm").asString() + ":" + c.get("thstrm_amount").asLong()));
        return out;
    }

    @Test
    void 미매핑_계정마다_같은_보고서의_원천_계정_후보를_보여준다() throws Exception {
        String corp = "00000201";
        company(corp, "매핑건설");
        jdbc.update("""
                INSERT INTO dart.fs_fetch (corp_code, bsns_year, reprt_code, status, fs_div, row_count, rcept_no)
                VALUES (?, '2025', '11011', 'OK', 'CFS', 6, '20260301000001')""", corp);
        raw(corp, "BS", 1, "ifrs-full_Assets", "자산총계", 1_000);
        raw(corp, "BS", 2, "ifrs-full_CurrentLiabilities", "유동부채", 300);
        raw(corp, "BS", 3, "dart_ShortTermBorrowings", "단기차입금", 50);
        raw(corp, "BS", 4, "-표준계정코드 미사용-", "유동성사채", 20);
        raw(corp, "IS", 1, "ifrs-full_Revenue", "매출액", 500);
        raw(corp, "CIS", 1, "ifrs-full_FinanceCosts", "금융원가", 10);
        raw(corp, "CF", 1, "ifrs-full_CashFlowsFromUsedInOperatingActivities", "영업활동현금흐름", -40);
        jdbc.update("""
                INSERT INTO dart.fs_std (corp_code, period_key, fs_div, std_code, basis, amount, rcept_no, reprt_code)
                VALUES (?, '2025Q4', 'CFS', 'TOTAL_ASSETS', 'POINT', 1000, '20260301000001', '11011')""", corp);

        String body = mvc.perform(get("/api/v1/mapping/unmapped")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode accounts = mapper.readTree(body).get("accounts");

        assertThat(account(accounts, "TOTAL_ASSETS")).isNull();
        assertThat(account(accounts, "CURRENT_LIABILITIES").get("corp_name").asString()).isEqualTo("매핑건설");
        assertThat(account(accounts, "CURRENT_LIABILITIES").get("periods").asString()).isEqualTo("2025Q4");
        assertThat(names(account(accounts, "CURRENT_LIABILITIES").get("candidates"))).containsExactly("BS:유동부채:300");
        // 이름 힌트(차입|사채)로 고르고, 계정명 순서
        assertThat(names(account(accounts, "SHORT_BORROWINGS").get("candidates"))).containsExactly("BS:단기차입금:50", "BS:유동성사채:20");
        // 손익은 IS · CIS 둘 다에서 찾음
        assertThat(names(account(accounts, "INTEREST_EXPENSE").get("candidates"))).containsExactly("CIS:금융원가:10");
        assertThat(names(account(accounts, "REVENUE").get("candidates"))).containsExactly("IS:매출액:500");
        assertThat(names(account(accounts, "OPERATING_CASH_FLOW").get("candidates"))).containsExactly("CF:영업활동현금흐름:-40");
        assertThat(account(accounts, "CURRENT_LIABILITIES").has("latest")).isFalse();
        JsonNode c = account(accounts, "CURRENT_LIABILITIES").get("candidates").get(0);
        assertThat(c.get("account_id").asString()).isEqualTo("ifrs-full_CurrentLiabilities");
    }

    @Test
    void 지역_코드를_다시_매핑하면_옛_지역에_남은_그_출처의_통계를_지운다() throws Exception {
        // 이름 매칭이 X 를 A 에 잘못 붙였다가 관리자가 B 로 고쳐도, A 에 X 의 수치가 계속 남아 A 의 지표 · 경보가 틀렸음 (VERIFICATION 130)
        jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level) VALUES ('95101', '가구', '시험시 가구', '95', '시험시', 2), ('95102', '나구', '시험시 나구', '95', '시험시', 2)");
        try {
            jdbc.update("INSERT INTO ref.region_code_map (source, source_code, source_name, region_cd, match_method) VALUES ('KOSIS', 'T-X', '시험 > 나구', '95101', 'NAME'), ('KOSIS', 'T-Y', '시험 > 가구', '95101', 'NAME')");
            jdbc.update("INSERT INTO mkt.region_stat (series_id, region_cd, period, value, source_code) VALUES ('KOSIS_UNSOLD', '95101', '202606', 100, 'T-X'), ('KOSIS_UNSOLD', '95101', '202605', 90, 'T-Y')");
            String gen = redis.opsForValue().get("br:gen");
            mvc.perform(put("/api/v1/mapping/region-codes").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"source\":\"KOSIS\",\"sourceCode\":\"T-X\",\"regionCd\":\"95102\"}")).andExpect(status().isOk());
            assertThat(redis.opsForValue().get("br:gen")).isNotEqualTo(gen);          // 지역 상세 캐시가 옛 매핑을 보여 주지 않게 (138)
            assertThat(count("SELECT count(*) FROM mkt.region_stat WHERE region_cd = '95101' AND source_code = 'T-X'")).isZero();
            assertThat(count("SELECT count(*) FROM mkt.region_stat WHERE region_cd = '95101' AND source_code = 'T-Y'")).isEqualTo(1);   // 다른 코드의 값은 그대로
        } finally {
            jdbc.update("DELETE FROM mkt.region_stat WHERE region_cd IN ('95101', '95102')");
            jdbc.update("DELETE FROM ref.region_code_map WHERE source_code IN ('T-X', 'T-Y')");
            jdbc.update("DELETE FROM ref.region WHERE region_cd IN ('95101', '95102')");
        }
    }

    @Test
    void 매핑_규칙은_화면에서_추가_삭제하고_같은_규칙은_우선순위만_바꾸며_seed_규칙은_지울_수_없다() throws Exception {
        String rule = """
                {"stdCode":"TOTAL_ASSETS","sjDiv":"BS","matchType":"NAME_EXACT","pattern":"자산합계(시험)","priority":40}""";
        JsonNode added = mapper.readTree(mvc.perform(post("/api/v1/mapping/account-rules").header("X-Admin-Token", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(rule))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        int id = added.get("mapId").asInt();
        assertThat(jdbc.queryForObject("SELECT origin || ':' || priority FROM ref.account_map WHERE map_id = ?", String.class, id))
                .isEqualTo("USER:40");
        mvc.perform(post("/api/v1/mapping/account-rules").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(rule.replace("40", "35")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mapId").value(id));          // 같은 규칙 → 우선순위만
        assertThat(jdbc.queryForObject("SELECT priority FROM ref.account_map WHERE map_id = ?", Integer.class, id)).isEqualTo(35);

        for (String bad : List.of("{\"stdCode\":\"NO_SUCH\",\"matchType\":\"NAME_EXACT\",\"pattern\":\"x\"}",
                "{\"stdCode\":\"TOTAL_ASSETS\",\"matchType\":\"NAME_REGEX\",\"pattern\":\"(\"}",
                "{\"stdCode\":\"TOTAL_ASSETS\",\"matchType\":\"NAME_EXACT\",\"pattern\":\"x\",\"sjDiv\":\"XX\"}")) {
            mvc.perform(post("/api/v1/mapping/account-rules").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                    .content(bad)).andExpect(status().isBadRequest());
        }

        int seed = jdbc.queryForObject("SELECT min(map_id) FROM ref.account_map WHERE origin <> 'USER'", Integer.class);
        mvc.perform(delete("/api/v1/mapping/account-rules/" + seed).header("X-Admin-Token", ADMIN)).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/mapping/account-rules/" + id).header("X-Admin-Token", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(id));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ref.account_map WHERE map_id = ?", Integer.class, id)).isZero();
        mvc.perform(delete("/api/v1/mapping/account-rules/" + id).header("X-Admin-Token", ADMIN)).andExpect(status().isNotFound());
    }

    /**
     * seed 규칙과 같은 규칙을 화면에서 추가하면 seed 행의 우선순위를 바꾸고 201 로 seed 행 ID 를 돌려줬음(지우면 400, 다음 기동에 조용히 되돌아감).
     * sj_div 가 빈 규칙은 유니크 제약이 NULL 을 서로 다르게 봐 같은 규칙이 중복으로 쌓였음 (VERIFICATION 185)
     */
    @Test
    void seed_와_같은_규칙은_거절하고_sj_div_가_빈_같은_규칙도_하나로_본다() throws Exception {
        var seedBs = jdbc.queryForMap("SELECT map_id, priority FROM ref.account_map WHERE origin = 'SEED' AND std_code = 'TOTAL_ASSETS' "
                + "AND match_type = 'NAME_EXACT' AND pattern = '자산총계' AND sj_div = 'BS'");
        mvc.perform(post("/api/v1/mapping/account-rules").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stdCode\":\"TOTAL_ASSETS\",\"sjDiv\":\"BS\",\"matchType\":\"NAME_EXACT\",\"pattern\":\"자산총계\",\"priority\":5}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("seed")));
        assertThat(jdbc.queryForObject("SELECT priority FROM ref.account_map WHERE map_id = ?", Integer.class, seedBs.get("map_id")))
                .isEqualTo(((Number) seedBs.get("priority")).intValue());

        String nullSeed = "{\"stdCode\":\"REVENUE\",\"matchType\":\"ACCOUNT_ID\",\"pattern\":\"ifrs-full_Revenue\",\"priority\":5}";
        mvc.perform(post("/api/v1/mapping/account-rules").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content(nullSeed)).andExpect(status().isBadRequest());
        String userNull = "{\"stdCode\":\"REVENUE\",\"matchType\":\"NAME_EXACT\",\"pattern\":\"매출(시험)\",\"priority\":40}";
        for (String body : List.of(userNull, userNull.replace("40", "35"))) {
            mvc.perform(post("/api/v1/mapping/account-rules").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isCreated());
        }
        assertThat(jdbc.queryForList("SELECT origin || ':' || priority FROM ref.account_map WHERE std_code = 'REVENUE' "
                + "AND (pattern = 'ifrs-full_Revenue' AND match_type = 'ACCOUNT_ID' OR pattern = '매출(시험)') ORDER BY origin", String.class))
                .containsExactly("SEED:10", "USER:35");
        jdbc.update("DELETE FROM ref.account_map WHERE origin = 'USER'");
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.buildrisk.radar.company.data.FsRepository fsRepo;
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.transaction.PlatformTransactionManager txManager;

    /**
     * api · worker 가 동시에 기동해 seed 적재가 겹쳐도 sj_div 가 빈 새 seed 규칙이 하나만 생김 — 화면 경로(185)만 잠그고 seed 경로는
     * 잠그지 않아, 한쪽이 넣고 커밋하기 전에 다른 쪽도 '없음'으로 보고 넣었음 (VERIFICATION 210)
     */
    @Test
    void 겹친_seed_적재도_sj_div_가_빈_같은_규칙을_하나만_만든다() throws Exception {
        var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        var inserted = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> tx.execute(st -> {
                fsRepo.syncSeedMapRule("REVENUE", null, "NAME_EXACT", "매출(겹침 시험)", 45, false, null, null);
                inserted.countDown();
                try {
                    Thread.sleep(500);                                       // 커밋 전 — 다른 적재가 끼어듦
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            inserted.await();
            var second = pool.submit(() -> tx.execute(st ->
                    fsRepo.syncSeedMapRule("REVENUE", null, "NAME_EXACT", "매출(겹침 시험)", 45, false, null, null)));
            first.get();
            second.get();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ref.account_map WHERE pattern = '매출(겹침 시험)'", Integer.class)).isEqualTo(1);
        jdbc.update("DELETE FROM ref.account_map WHERE pattern = '매출(겹침 시험)'");
    }
}
