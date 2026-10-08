package com.buildrisk.radar.batch;

import com.buildrisk.radar.domain.region.SiteAddressResolver.Match;
import com.buildrisk.radar.domain.region.SiteLocationRepository;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-026 사업장 위치: 공급계약 주소의 법정동 · 지번을 일반구 실거래와 대조해 저장 → 사업장 카드 API 가 그 동의 시장을 계산.
 * 합성시(화면 단위, 실거래는 일반구 갑구) · 기준 월 2026-06 (region_stat), 최근 12개월 = 2025-07~2026-06, 그 전 = 2024-07~2025-06
 */
@AutoConfigureMockMvc
class SiteLocationIT extends IntegrationTest {
    static final String CORP = "00000801";
    @Autowired
    SiteLocationRepository sites;
    @Autowired
    MockMvc mvc;
    int seq;

    @BeforeEach
    void setUp() {
        cleanup();
        jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level, synthetic) VALUES ('99200', '합성시', '가상도 합성시', '99', '가상도', 2, true)");
        jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level, parent_cd) VALUES ('99201', '합성시 갑구', '가상도 합성시 갑구', '99', '가상도', 3, '99200')");
        jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level) VALUES ('99101', '가상구', '가상시 가상구', '99', '가상시', 2)");
        company(CORP, "위치건설");
        // 감천동 94-1(감천현대): 최근 12개월 거래 3건(50,000 · 60,000 · 70,000만원 / 84㎡) + 해제 1건, 그 전 12개월 2건(40,000 · 45,000)
        trade("99201", "2025-08-10", "감천동", "94-1", "감천현대", 50_000, false);
        trade("99201", "2025-12-03", "감천동", "94-1", "감천현대", 60_000, false);
        trade("99201", "2026-05-20", "감천동", "94-1", "감천현대", 70_000, false);
        trade("99201", "2026-03-02", "감천동", "94-1", "감천현대", 65_000, true);
        trade("99201", "2024-09-01", "감천동", "100", "감천삼익", 40_000, false);
        trade("99201", "2025-02-11", "감천동", "100", "감천삼익", 45_000, false);
        trade("99201", "2026-07-15", "감천동", "100", "감천삼익", 99_000, false);   // 기준 월 뒤 — 세지 않음
        jdbc.update("INSERT INTO mkt.region_stat (series_id, region_cd, period, value, source_code) VALUES ('RTMS_TRADE_CNT', '99200', '202606', 3, 'RTMS')");
        contract("20260101000801", "가상도 합성시 갑구 감천동 94-1번지 일원", "99200", 120_000_000_000L);
        contract("20260101000802", "가상도 합성시 갑구 없는동 1 일대", "99200", 50_000_000_000L);
        contract("20260101000803", "가상시 가상구", "99101", 30_000_000_000L);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM mkt.apt_trade WHERE lawd_cd LIKE '99%'");
        jdbc.update("DELETE FROM mkt.region_stat WHERE region_cd LIKE '99%'");
        jdbc.update("DELETE FROM dart.contract WHERE region_cd LIKE '99%'");
        jdbc.update("DELETE FROM ref.region WHERE region_cd LIKE '99%' AND level = 3");
        jdbc.update("DELETE FROM ref.region WHERE region_cd LIKE '99%'");
    }

    private void trade(String lawd, String date, String umd, String jibun, String apt, long amount, boolean cancelled) {
        LocalDate d = LocalDate.parse(date);
        jdbc.update("""
                INSERT INTO mkt.apt_trade (lawd_cd, deal_ym, seq, deal_date, umd_nm, apt_nm, jibun, exclu_use_ar, deal_amount, cancelled)
                VALUES (?, ?, ?, ?, ?, ?, ?, 84, ?, ?)""", lawd, d.getYear() * 100 + d.getMonthValue(), ++seq, d, umd, apt, jibun, amount, cancelled);
    }

    private void contract(String rcept, String text, String regionCd, long amount) {
        jdbc.update("INSERT INTO dart.disclosure (rcept_no, corp_code, report_nm, rcept_dt, event_type) VALUES (?, ?, '단일판매ㆍ공급계약체결', '2026-01-01', 'CONTRACT')", rcept, CORP);
        jdbc.update("INSERT INTO dart.filing_doc (rcept_no, corp_code, kind, status, parser_version) VALUES (?, ?, 'CONTRACT', 'PARSED', 1)", rcept, CORP);
        jdbc.update("""
                INSERT INTO dart.contract (rcept_no, corp_code, rcept_dt, contract_name, name_key, amount, region_text, region_cd, region_match)
                VALUES (?, ?, '2026-01-01', ?, ?, ?, ?, ?, 'SIGUNGU')""", rcept, CORP, "공사 " + rcept, "공사" + rcept, amount, text, regionCd);
    }

    @Test
    void 법정동_지번을_일반구_실거래와_대조해_저장하고_카드_API_가_그_동의_시장을_계산() throws Exception {
        Map<Match, Integer> counts = sites.relocateAll();
        assertThat(counts).containsEntry(Match.JIBUN, 1).containsEntry(Match.UMD_NO_TRADE, 1).containsEntry(Match.NONE, 1);
        var row = jdbc.queryForMap("SELECT site_lawd_cd, site_umd, site_jibun, site_apt, site_match FROM dart.contract WHERE rcept_no = '20260101000801'");
        assertThat(row).containsEntry("site_lawd_cd", "99201").containsEntry("site_umd", "감천동").containsEntry("site_jibun", "94-1")
                .containsEntry("site_apt", "감천현대").containsEntry("site_match", "JIBUN");

        mvc.perform(get("/api/v1/companies/" + CORP + "/sites")).andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage.total").value(3))
                .andExpect(jsonPath("$.coverage.jibun").value(1))
                .andExpect(jsonPath("$.items[0].match").value("JIBUN"))
                .andExpect(jsonPath("$.items[0].market.fromYm").value(202507))
                .andExpect(jsonPath("$.items[0].market.toYm").value(202606))
                .andExpect(jsonPath("$.items[0].market.trades").value(3))                 // 해제 · 기준 월 뒤 거래 제외
                .andExpect(jsonPath("$.items[0].market.tradesPrev").value(2))
                .andExpect(jsonPath("$.items[0].market.tradesYoyPct").value(50.0))
                .andExpect(jsonPath("$.items[0].market.cancelRatePct").value(25.0))       // 1 / (3 + 1)
                .andExpect(jsonPath("$.items[0].market.medianPriceM2").value(714.3))      // 60,000만원 / 84㎡
                .andExpect(jsonPath("$.items[0].market.medianPriceM2Prev").value(506.0))  // (476.2 + 535.7) / 2
                .andExpect(jsonPath("$.items[0].complex.aptNm").value("감천현대"))
                .andExpect(jsonPath("$.items[0].complex.trades").value(3))
                .andExpect(jsonPath("$.items[0].complex.lastDealDate").value("2026-05-20"))
                .andExpect(jsonPath("$.items[1].match").value("UMD_NO_TRADE"))
                .andExpect(jsonPath("$.items[1].market").doesNotExist());
        mvc.perform(get("/api/v1/regions/99200/sites")).andExpect(jsonPath("$.coverage.total").value(2));
        mvc.perform(get("/api/v1/companies/99999999/sites")).andExpect(status().isNotFound());
    }
}
