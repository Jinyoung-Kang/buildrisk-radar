package com.buildrisk.radar.api;

import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.common.cache.JsonCache;
import com.buildrisk.radar.common.Kst;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-032 — 기업 분양 탭의 '위험 지역 분양 비중' · 노출 표의 분양 열 · 경보 R-X02 가 같은 숫자 (R-X01 의 VERIFICATION 187 원칙).
 * 미분양 지표가 없는 지역 · 시도만 풀린 공고 · 사라진 공고 · 기간 밖 공고는 비중에서 빠지고, 근거 계보는 공고 → 시공사 대조 → 지역 지표로 이어짐
 */
@AutoConfigureMockMvc
class PresaleShareIT extends IntegrationTest {
    private static final String CORP = "00000801";
    @Autowired
    MockMvc mvc;
    @Autowired
    BatchLauncher launcher;
    @Autowired
    JsonCache cache;

    @BeforeEach
    void seed() {
        removeRegions();
        cache.invalidateAll();                      // 앞선 테스트가 남긴 /exposure · 기업 조회 캐시(같은 JVM · 같은 Redis)
        company(CORP, "분양건설");
        jdbc.update("""
                INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level) VALUES
                ('98301', '위험구', '분양시 위험구', '98', '분양시', 2), ('98302', '안전구', '분양시 안전구', '98', '분양시', 2),
                ('98303', '무지표구', '분양시 무지표구', '98', '분양시', 2)""");
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.calc_run (calc_run_id, kind) VALUES (?, 'METRIC')", run);
        jdbc.update("""
                INSERT INTO mkt.region_metric (region_cd, period, metric_code, value, status, calc_run_id)
                VALUES ('98301', '202608', 'UNSOLD_PER_1K_HH', 6.0, 'OK', ?), ('98302', '202608', 'UNSOLD_PER_1K_HH', 1.0, 'OK', ?)""", run, run);
        LocalDate today = Kst.today();
        notice("N1", today.minusDays(30), "98301", 600, false);
        notice("N2", today.minusDays(60), "98301", 400, false);
        notice("N3", today.minusDays(20), "98302", 1000, false);
        notice("N4", today.minusDays(10), "98303", 500, false);      // 미분양 지표 없는 지역 — 분모 제외
        notice("N5", today.minusDays(500), "98301", 900, false);     // 최근 1년 밖
        notice("N6", today.minusDays(15), "98301", 700, true);       // 사라진 공고(정정 · 취소)
        notice("N7", today.minusDays(5), null, 300, false);          // 시도만 풀림
        builder("N1", 2, "(주)파트너건설", null, "UNMATCHED");
        jdbc.update("""
                INSERT INTO mkt.presale_remainder (house_manage_no, pblanc_no, house_secd, house_nm, tot_supply_hh, notice_date, raw, raw_sha256,
                  region_cd, region_match, notice_house_manage_no, notice_pblanc_no, link_method)
                VALUES ('R1', 'R1', '04', '단지N1', 35, ?, '{}', repeat('0', 64), '98301', 'SIGUNGU', 'N1', 'N1', 'NAME_ZIP')""", today.minusDays(3));
    }

    private void notice(String no, LocalDate date, String region, int hh, boolean removed) {
        jdbc.update("""
                INSERT INTO mkt.presale_notice (house_manage_no, pblanc_no, house_nm, house_secd, notice_date, tot_supply_hh, address, builder_name,
                  movein_ym, raw, raw_sha256, region_cd, region_match, removed_at)
                VALUES (?, ?, ?, '01', ?, ?, '분양시 어딘가', ?, '202912', '{}', repeat('0', 64), ?, ?, ?)""",
                no, no, "단지" + no, date, hh, no.equals("N1") ? "분양건설(주), (주)파트너건설" : "분양건설(주)", region,
                region == null ? "SIDO" : "SIGUNGU", removed ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
        builder(no, 1, "분양건설(주)", CORP, "MATCHED");
    }

    private void builder(String no, int seq, String name, String corp, String status) {
        jdbc.update("""
                INSERT INTO mkt.presale_notice_builder (house_manage_no, pblanc_no, seq, name_raw, name_key, corp_code, match_status, match_method,
                  matcher_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)""",
                no, no, seq, name, name, corp, status, corp == null ? null : "EXACT");
    }

    @AfterEach
    void removeRegions() {
        jdbc.update("DELETE FROM mkt.presale_notice");
        jdbc.update("DELETE FROM mkt.presale_remainder");
        jdbc.update("DELETE FROM mkt.region_metric WHERE region_cd LIKE '983%'");
        jdbc.update("DELETE FROM risk.alert WHERE target_key = ?", CORP);
        jdbc.update("DELETE FROM ref.region WHERE region_cd LIKE '983%'");
    }

    @Test
    void 분양_탭_노출_표_경보가_같은_비중이고_계보는_공고와_지역_지표로_이어진다() throws Exception {
        mvc.perform(get("/api/v1/companies/" + CORP + "/presales")).andExpect(status().isOk())
                .andExpect(jsonPath("$.share.windowDays").value(365))
                .andExpect(jsonPath("$.share.notices").value(3))
                .andExpect(jsonPath("$.share.totalUnits").value(2000))
                .andExpect(jsonPath("$.share.riskUnits").value(1000))
                .andExpect(jsonPath("$.share.sharePct").value(50.0))
                .andExpect(jsonPath("$.share.excluded").value(1))
                .andExpect(jsonPath("$.items", hasSize(7)))
                .andExpect(jsonPath("$.items[?(@.houseManageNo == 'N6')].removed").value(true))
                .andExpect(jsonPath("$.items[?(@.houseManageNo == 'N1')].consortium").value(2))
                .andExpect(jsonPath("$.items[?(@.houseManageNo == 'N1')].partners[0].name").value("(주)파트너건설"))
                .andExpect(jsonPath("$.items[?(@.houseManageNo == 'N1')].remainderUnits").value(35));
        mvc.perform(get("/api/v1/exposure")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].presaleNotices").value(5))
                .andExpect(jsonPath("$.items[0].presaleUnits").value(2800))
                .andExpect(jsonPath("$.items[0].presaleRiskUnits").value(1000))
                .andExpect(jsonPath("$.items[0].presaleRiskSharePct").value(50.0))
                .andExpect(jsonPath("$.items[0].remainderUnits").value(35));
        mvc.perform(get("/api/v1/regions/98301/presales")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))                         // N1 · N2 (N5 기간 밖 · N6 사라짐)
                .andExpect(jsonPath("$.companies[0].corpCode").value(CORP))
                .andExpect(jsonPath("$.companies[0].units").value(1000))
                .andExpect(jsonPath("$.remainder.notices").value(1));

        assertThat(launcher.runAndWait("ruleEvalJob", Map.of()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Long alertId = jdbc.queryForObject("SELECT alert_id FROM risk.alert WHERE rule_code = 'R-X02' AND target_key = ?", Long.class, CORP);
        assertThat(jdbc.queryForObject("SELECT title FROM risk.alert WHERE alert_id = ?", String.class, alertId)).contains("50");
        mvc.perform(get("/api/v1/alerts/" + alertId + "/lineage")).andExpect(status().isOk())
                .andExpect(jsonPath("$.presales", hasSize(2)))
                .andExpect(jsonPath("$.presales[0].builders[0].corpCode").value(CORP))
                .andExpect(jsonPath("$.metrics[?(@.metricCode == 'UNSOLD_PER_1K_HH' && @.targetKey == '98301')]", hasSize(1)));
    }
}
