package com.buildrisk.radar.api;

import com.buildrisk.radar.common.cache.JsonCache;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 익명 GET 이 조회 캐시 키를 무한히 만들지 못함 (VERIFICATION 105) — 캐시는 세션 · 로그인 잠금과 같은 Redis(allkeys-lru)를 쓰므로
 * 키 공간이 열려 있으면 공격자가 세션을 밀어낼 수 있음. 모르는 열거 값은 400, 화면이 쓰지 않는 조합 · 빈 결과는 캐시하지 않음.
 */
@AutoConfigureMockMvc
class CacheKeyIT extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    JsonCache cache;

    @BeforeEach
    void freshGeneration() { cache.invalidateAll(); }

    /** 지역은 테스트 사이에 비우지 않음 — 남기면 실거래 Job 테스트의 호출 수가 달라짐 */
    @AfterEach
    void removeRegion() {
        jdbc.update("DELETE FROM mkt.region_metric WHERE region_cd = '98101'");
        jdbc.update("DELETE FROM ref.region WHERE region_cd = '98101'");
    }

    private Set<String> keys(String prefix) {
        return redis.keys("br:" + redis.opsForValue().get("br:gen") + ":" + prefix + "*");
    }

    private void alert(String corp) {
        company(corp, "캐시건설");
        jdbc.update("""
                INSERT INTO risk.alert (rule_code, rule_version, target_type, target_key, as_of, severity, title, message, evidence, status)
                VALUES ('R-C02', 1, 'COMPANY', ?, '2026Q2', 'HIGH', 't', 'm', '{"condition":"x"}', 'OPEN')""", corp);
    }

    @Test
    void 목록_필터의_모르는_값은_400_이고_캐시에_남지_않는다() throws Exception {
        for (String q : List.of("status=OPEN,x1", "status=open", "targetType=FOO", "severity=CRITICAL")) {
            mvc.perform(get("/api/v1/alerts?" + q)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mvc.perform(get("/api/v1/export/alerts.csv?status=FOO")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/companies?sort=x1")).andExpect(status().isBadRequest());
        assertThat(keys("alerts:")).isEmpty();
        assertThat(keys("companies:")).isEmpty();
    }

    @Test
    void 같은_조건은_표기가_달라도_캐시_키_하나() throws Exception {
        alert("00000101");
        for (String st : List.of("OPEN,ACK", "ACK,OPEN", "OPEN,ACK,OPEN", "OPEN, ACK")) {
            mvc.perform(get("/api/v1/alerts").param("status", st)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
        }
        assertThat(keys("alerts:")).hasSize(1);
    }

    @Test
    void 화면이_쓰지_않는_쪽_크기와_빈_쪽은_캐시하지_않는다() throws Exception {
        alert("00000102");
        mvc.perform(get("/api/v1/alerts?size=7")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/alerts?page=50&size=100")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/alerts?page=5&size=50")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/companies?size=7")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/companies?page=3&size=100")).andExpect(status().isOk());
        assertThat(keys("alerts:")).isEmpty();
        assertThat(keys("companies:")).isEmpty();

        mvc.perform(get("/api/v1/alerts?size=100")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/companies?size=100")).andExpect(status().isOk());
        assertThat(keys("alerts:")).hasSize(1);
        assertThat(keys("companies:")).hasSize(1);
    }

    @Test
    void 지역_목록은_지역이_있는_최신_기간만_캐시() throws Exception {
        jdbc.update("""
                INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level)
                VALUES ('98101', '캐시구', '캐시시 캐시구', '98', '캐시시', 2)""");
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.calc_run (calc_run_id, kind) VALUES (?, 'METRIC')", run);
        jdbc.update("""
                INSERT INTO mkt.region_metric (region_cd, period, metric_code, value, status, calc_run_id)
                VALUES ('98101', '202606', 'UNSOLD_PER_1K_HH', 3.2, 'OK', ?), ('98101', '202605', 'UNSOLD_PER_1K_HH', 3.1, 'OK', ?)""", run, run);
        mvc.perform(get("/api/v1/regions?period=209912")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/regions?sido=97")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/regions?period=202605")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.regionCd == '98101')].value").value(3.1));
        assertThat(keys("regions:")).isEmpty();

        mvc.perform(get("/api/v1/regions")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/regions?period=202606")).andExpect(status().isOk());
        assertThat(keys("regions:")).hasSize(2);
    }

    @Test
    void 노출_주가는_화면이_고르는_값만_캐시() throws Exception {
        company("00000103", "캐시건설");
        mvc.perform(get("/api/v1/exposure?days=200&unsoldPer1kHh=4.5")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/companies/00000103/prices?days=31")).andExpect(status().isOk());
        assertThat(keys("exposure:")).isEmpty();
        assertThat(keys("prices:")).isEmpty();

        mvc.perform(get("/api/v1/exposure?days=365&unsoldPer1kHh=5")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/companies/00000103/prices?days=1095")).andExpect(status().isOk());
        assertThat(keys("exposure:")).hasSize(1);
        assertThat(keys("prices:")).hasSize(1);
    }

    /**
     * 기업별 조회는 대상 기업(유니버스)만 캐시 — ref.company 에는 DART 의 모든 회사(약 12만)가 있어, 존재만 보고 저장하면
     * 코드를 바꿔 가며 부르는 것만으로 키가 그만큼 쌓였음(실측 200곳 × 5개 조회 → 키 847개 · 849 KB) (VERIFICATION 175)
     */
    @Test
    void 대상이_아닌_기업은_기업별_조회를_캐시하지_않는다() throws Exception {
        jdbc.update("""
                INSERT INTO ref.company (corp_code, corp_name, stock_code, corp_cls, induty_code, is_target, target_reason)
                VALUES ('00000105', '비대상상사', '000105', 'Y', '46101', false, NULL)""");
        for (String p : List.of("", "/filings", "/pf", "/prices", "/prices?days=1095", "/sites")) {
            mvc.perform(get("/api/v1/companies/00000105" + p)).andExpect(status().isOk());
        }
        for (String prefix : List.of("company:", "filings:", "pf:", "prices:", "sites:c:")) {
            assertThat(keys(prefix)).as(prefix).isEmpty();
        }

        company("00000106", "대상건설");
        for (String p : List.of("", "/filings", "/pf", "/prices", "/sites")) {
            mvc.perform(get("/api/v1/companies/00000106" + p)).andExpect(status().isOk());
        }
        for (String prefix : List.of("company:", "filings:", "pf:", "prices:", "sites:c:")) {
            assertThat(keys(prefix)).as(prefix).hasSize(1);
        }
    }

    /**
     * 규칙(임계값 · 사용 여부)이나 계정 매핑 규칙을 바꾸면 조회 캐시를 비움 — 성적표 · 대시보드 요약은 현재 규칙으로 다시 평가하는 화면인데
     * 최대 30분 옛 규칙의 결과가, 근거 계보의 reproduced 는 10분 옛 값이 나갔음 (VERIFICATION 186)
     */
    @Test
    void 규칙과_계정_매핑_규칙을_바꾸면_조회_캐시를_비운다() throws Exception {
        java.util.function.Supplier<String> gen = () -> redis.opsForValue().get("br:gen");
        String g0 = gen.get();
        mvc.perform(put("/api/v1/rules/R-C02").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"params\":{\"threshold\":0.75},\"changeNote\":\"캐시 시험\"}")).andExpect(status().isOk());
        String g1 = gen.get();
        assertThat(g1).as("규칙 변경").isNotEqualTo(g0);

        String rule = "{\"stdCode\":\"TOTAL_ASSETS\",\"sjDiv\":\"BS\",\"matchType\":\"NAME_EXACT\",\"pattern\":\"자산합계(캐시)\"}";
        String added = mvc.perform(post("/api/v1/mapping/account-rules").header("X-Admin-Token", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(rule)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String g2 = gen.get();
        assertThat(g2).as("매핑 규칙 추가").isNotEqualTo(g1);
        int mapId = Integer.parseInt(added.replaceAll(".*\"mapId\":(\\d+).*", "$1"));
        mvc.perform(delete("/api/v1/mapping/account-rules/" + mapId).header("X-Admin-Token", ADMIN)).andExpect(status().isOk());
        assertThat(gen.get()).as("매핑 규칙 삭제").isNotEqualTo(g2);
    }

    /**
     * 종목코드가 없는(비상장) 기업의 주가는 빈 결과 — 종목코드가 NULL 인 행을 '행 없음'으로 읽어 404 '기업을 찾을 수 없습니다'가 났음.
     * 없는 기업은 그대로 404 (VERIFICATION 190)
     */
    @Test
    void 비상장_기업의_주가는_404_가_아니라_빈_결과() throws Exception {
        jdbc.update("""
                INSERT INTO ref.company (corp_code, corp_name, stock_code, corp_cls, induty_code, is_target, target_reason)
                VALUES ('00000107', '비상장건설', NULL, 'E', '41221', false, NULL)""");
        mvc.perform(get("/api/v1/companies/00000107/prices")).andExpect(status().isOk())
                .andExpect(jsonPath("$.bars").isEmpty()).andExpect(jsonPath("$.stockCode").doesNotExist());
        mvc.perform(get("/api/v1/companies/09999999/prices")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMPANY_NOT_FOUND"));
    }

    @Test
    void 무효화하면_지난_세대의_키를_지운다() throws Exception {
        company("00000104", "캐시건설");
        mvc.perform(get("/api/v1/companies/00000104")).andExpect(status().isOk());
        String old = redis.opsForValue().get("br:gen");
        assertThat(redis.keys("br:" + old + ":*")).isNotEmpty();
        // TTL 을 기다리면 무효화가 잦을 때 세대마다 키가 겹쳐 쌓임(실제 스택에서 128 MB 상한까지 차 세션이 밀려날 수 있었음)
        cache.invalidateAll();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(redis.keys("br:" + old + ":*")).isEmpty());
    }
}
