package com.buildrisk.radar.batch;

import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.metric.data.MetricRepository;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-024 — 정기보고서 document.xml(Zip)에서 우발부채 구간만 꺼내 PF 구조화 → PF 지표(자본총계 대비) → R-C06 경보 → 기업 PF API.
 * fixture 는 태영건설 2026 반기보고서의 PF 부분 (보증 2조 3,544억 · 12개월 내 만기 1조 3,122억 · 사업장 31곳)
 */
@AutoConfigureMockMvc
class PfExposureJobIT extends IntegrationTest {
    static final String DOC = "/api/document.xml", CORP = "00000601";
    static final String ORIGINAL = "20260814003287", CORRECTED = "20260901000123", OLD = "20221114000456";

    @Autowired
    BatchLauncher launcher;
    @Autowired
    MetricRepository metrics;
    @Autowired
    MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        company(CORP, "가상건설");
        disclosure(ORIGINAL, "반기보고서 (2026.06)", "2026-08-14");
        disclosure(CORRECTED, "[기재정정]반기보고서 (2026.06)", "2026-09-01");    // 같은 분기의 최신 제출본만 받음
        disclosure(OLD, "분기보고서 (2022.09)", "2022-11-14");                  // 기본 fromPeriod(2023Q4) 이전
        stubDoc(CORRECTED, "/fixtures/pf/taeyoung-2026q2.xml");
        equity("2026Q2", "740900000000");
    }

    private void disclosure(String no, String name, String date) {
        jdbc.update("INSERT INTO dart.disclosure (rcept_no, corp_code, report_nm, rcept_dt, event_type) VALUES (?, ?, ?, ?::date, 'PERIODIC')",
                no, CORP, name, date);
    }

    private void equity(String pk, String amount) {
        jdbc.update("""
                INSERT INTO dart.fs_std (corp_code, period_key, fs_div, std_code, basis, amount, rcept_no, reprt_code)
                VALUES (?, ?, 'CFS', 'TOTAL_EQUITY', 'POINT', ?, ?, '11012')
                ON CONFLICT (corp_code, period_key, fs_div, std_code, basis) DO UPDATE SET amount = EXCLUDED.amount""",
                CORP, pk, new BigDecimal(amount), CORRECTED);
    }

    /** 실제 정기보고서처럼 앞 · 뒤 장(XI-1 · XI-3) 사이에 구간을 끼워 Zip 으로 */
    private static void stubDoc(String no, String fixture) throws IOException {
        String section = new String(PfExposureJobIT.class.getResourceAsStream(fixture).readAllBytes(), StandardCharsets.UTF_8);
        String doc = "<DOCUMENT><BODY><TITLE ATOC=\"Y\" AASSOCNOTE=\"D-0-11-1-0\">1. 공시내용 진행 및 변경사항</TITLE><P>해당사항 없음</P>\n"
                + section + "\n<TITLE ATOC=\"Y\" AASSOCNOTE=\"D-0-11-3-0\">3. 제재 등과 관련된 사항</TITLE><P>해당사항 없음</P></BODY></DOCUMENT>";
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(no + ".xml"));
            zip.write(doc.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        WM.stubFor(get(urlPathEqualTo(DOC)).withQueryParam("rcept_no", equalTo(no))
                .willReturn(aResponse().withHeader("Content-Type", "application/zip").withBody(out.toByteArray())));
    }

    private BigDecimal metric(String code) {
        return jdbc.queryForObject("SELECT value FROM risk.company_metric WHERE corp_code = ? AND period_key = '2026Q2' AND metric_code = ?",
                BigDecimal.class, CORP, code);
    }

    @Test
    void 정기보고서_구간을_구조화해_PF_지표와_R_C06_경보를_만들고_API_로_보여준다() throws Exception {
        assertThat(launcher.runAndWait("pfExposureJob", Map.of()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        WM.verify(1, getRequestedFor(urlPathEqualTo(DOC)).withQueryParam("rcept_no", equalTo(CORRECTED)));
        WM.verify(0, getRequestedFor(urlPathEqualTo(DOC)).withQueryParam("rcept_no", equalTo(ORIGINAL)));
        WM.verify(0, getRequestedFor(urlPathEqualTo(DOC)).withQueryParam("rcept_no", equalTo(OLD)));
        var row = jdbc.queryForMap("SELECT status, period_key, guarantee, reconciled, section_html FROM dart.pf_report WHERE rcept_no = ?", CORRECTED);
        assertThat(row.get("status")).isEqualTo("PARSED");
        assertThat(row.get("period_key")).isEqualTo("2026Q2");
        assertThat((BigDecimal) row.get("guarantee")).isEqualByComparingTo("2354379000000");
        assertThat(row.get("reconciled")).isEqualTo(true);
        // 앞 · 뒤 장은 보관하지 않음 (구간만)
        assertThat((String) row.get("section_html")).contains("D-0-11-2-0").doesNotContain("D-0-11-1-0").doesNotContain("D-0-11-3-0");
        assertThat(count("SELECT count(*) FROM dart.pf_site WHERE rcept_no = ?", CORRECTED)).isEqualTo(31);

        // 다시 돌려도 받은 보고서는 다시 부르지 않음
        launcher.runAndWait("pfExposureJob", Map.of());
        WM.verify(1, getRequestedFor(urlPathEqualTo(DOC)).withQueryParam("rcept_no", equalTo(CORRECTED)));

        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.calc_run (calc_run_id, kind) VALUES (?, 'METRIC')", run);
        assertThat(metrics.insertPfMetrics(run)).isEqualTo(3);
        assertThat(metric("PF_GUARANTEE_TO_EQUITY")).isEqualByComparingTo("317.7728");      // 2,354,379 / 740,900
        assertThat(metric("PF_DUE_12M_TO_EQUITY")).isEqualByComparingTo("177.1028");        // 1,312,155 / 740,900
        // 다시 계산하면 최신 자본총계로 갱신 (옛 값이 남지 않음)
        equity("2026Q2", "1000000000000");
        assertThat(metrics.insertPfMetrics(run)).isEqualTo(3);
        assertThat(metric("PF_GUARANTEE_TO_EQUITY")).isEqualByComparingTo("235.4379");
        assertThat(count("SELECT count(*) FROM risk.company_metric WHERE corp_code = ? AND metric_code LIKE 'PF\\_%'", CORP)).isEqualTo(3);
        // 기업 지표를 다시 계산해도(앞 Step) PF 지표는 남음 — 전에는 기업 단위 DELETE 가 PF 까지 지우고 다음 Step 에서야 다시 넣어,
        // 그 사이(또는 다음 Step 실패 · 멈춤) 화면 · 규칙 평가에서 PF 지표가 비었음 (VERIFICATION 126)
        metrics.replaceCompanyMetrics(CORP, java.util.List.of(), run);
        assertThat(count("SELECT count(*) FROM risk.company_metric WHERE corp_code = ? AND metric_code LIKE 'PF\\_%'", CORP)).isEqualTo(3);

        assertThat(launcher.runAndWait("ruleEvalJob", Map.of()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT title FROM risk.alert WHERE rule_code = 'R-C06' AND target_key = ? AND as_of = '2026Q2'",
                String.class, CORP)).isEqualTo("PF 보증 자기자본의 235% · 12개월 내 만기 131%");

        mvc.perform(MockMvcRequestBuilders.get("/api/v1/companies/" + CORP + "/pf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.rceptNo").value(CORRECTED))
                .andExpect(jsonPath("$.current.guaranteeToEquityPct").value(235.4379))
                .andExpect(jsonPath("$.current.reconciled").value(true))
                .andExpect(jsonPath("$.sites", hasSize(31)))
                .andExpect(jsonPath("$.coverage.parsed").value(1));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/companies/99999999/pf")).andExpect(status().isNotFound());
    }
}
