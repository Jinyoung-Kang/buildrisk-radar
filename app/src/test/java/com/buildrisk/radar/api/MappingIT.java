package com.buildrisk.radar.api;

import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 매핑 화면 API — 표준계정별 매핑률과 미매핑 (기업 × 표준계정)의 원천 계정 후보 (FR-206) */
@AutoConfigureMockMvc
class MappingIT extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

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
}
