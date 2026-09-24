package com.buildrisk.radar.batch;

import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 11장 배치 통합: ① 청크 도중 실패 → restart → 중복·누락 0  ② 013 → OFS 대체 / 둘 다 없으면 스킵 기록
 * ③ 020 → STOPPED → 다음 실행에서 이어감.  대상: 기업 3 × 2024~2025 × 보고서 4 = 24 조합 (chunk 20)
 */
class FinancialStatementJobIT extends IntegrationTest {
    static final String FS = "/api/fnlttSinglAcntAll.json";
    static final Map<String, Object> RUN = Map.of("years", List.of(2024, 2025), "restart", false);
    static final int ROWS_PER_REPORT = 12;   // fixture 13행 중 자본변동표(SCE) 1행은 저장하지 않음

    @Autowired
    BatchLauncher launcher;
    @Autowired
    com.buildrisk.radar.api.BatchQueryService batchQuery;

    @BeforeEach
    void setUp() throws Exception {
        company("00000001", "가상건설");
        company("00000002", "예시종합건설");
        company("00000003", "샘플이앤씨");
        String ok = new String(getClass().getResourceAsStream("/fixtures/dart/fnltt_ok.json").readAllBytes(), StandardCharsets.UTF_8);
        WM.stubFor(get(urlPathEqualTo(FS)).atPriority(10).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(ok)));
    }

    private void status(String corp, String year, String reprt, String fsDiv, String status) {
        WM.stubFor(get(urlPathEqualTo(FS)).atPriority(1)
                .withQueryParam("corp_code", equalTo(corp)).withQueryParam("bsns_year", equalTo(year))
                .withQueryParam("reprt_code", equalTo(reprt)).withQueryParam("fs_div", equalTo(fsDiv))
                .withQueryParam("crtfc_key", equalTo("test-dart-key"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"" + status + "\",\"message\":\"테스트 " + status + "\"}")));
    }

    private void clearOverrides() {
        WM.getStubMappings().stream().filter(s -> s.getPriority() != null && s.getPriority() == 1).toList()
                .forEach(WM::removeStub);
    }

    @Test
    void 청크_도중_실패하면_마지막_커밋까지_남고_restart_로_나머지만_받는다() {
        status("00000003", "2024", "11013", "CFS", "100");   // 24번째(마지막) 조합에서 복구 불가 오류

        var first = launcher.runAndWait("financialStatementJob", RUN);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(count("SELECT count(*) FROM dart.fs_fetch")).isEqualTo(20);        // 첫 청크(20)만 커밋
        assertThat(jdbc.queryForObject("SELECT exit_message FROM ops.batch_job_execution WHERE job_execution_id = ?",
                String.class, first.getId())).startsWith("원인: DartApiException — DART 100");   // 근본 원인이 맨 앞

        clearOverrides();
        var second = launcher.runAndWait("financialStatementJob", Map.of());           // 같은 JobInstance restart
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(batchQuery.executions("financialStatementJob", 5).stream()
                .filter(e -> ((Number) e.get("jobExecutionId")).longValue() == first.getId()).findFirst().orElseThrow()
                .get("resolvedBy")).isEqualTo(second.getId());                           // 화면: 재시작으로 해결
        assertThat(count("SELECT count(*) FROM dart.fs_fetch WHERE status = 'OK'")).isEqualTo(24);
        assertThat(count("SELECT count(*) FROM dart.fs_raw")).isEqualTo(24 * ROWS_PER_REPORT);   // 중복 0
        assertThat(count("""
                SELECT count(*) FROM (SELECT corp_code, bsns_year, reprt_code, sj_div, line_no FROM dart.fs_raw
                GROUP BY 1, 2, 3, 4, 5 HAVING count(*) > 1) d""")).isZero();
        // 첫 실행에서 커밋된 조합은 다시 부르지 않음
        WM.verify(1, getRequestedFor(urlPathEqualTo(FS)).withQueryParam("corp_code", equalTo("00000001"))
                .withQueryParam("bsns_year", equalTo("2025")).withQueryParam("reprt_code", equalTo("11011")));
    }

    @Test
    void CFS_가_013_이면_OFS_로_받고_둘_다_없으면_NO_DATA_스킵() {
        status("00000001", "2024", "11014", "CFS", "013");
        status("00000002", "2024", "11012", "CFS", "013");
        status("00000002", "2024", "11012", "OFS", "013");

        var je = launcher.runAndWait("financialStatementJob", RUN);
        assertThat(je.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT fs_div FROM dart.fs_fetch WHERE corp_code = '00000001' AND bsns_year = '2024' AND reprt_code = '11014'",
                String.class)).isEqualTo("OFS");
        assertThat(jdbc.queryForObject("SELECT status FROM dart.fs_fetch WHERE corp_code = '00000002' AND bsns_year = '2024' AND reprt_code = '11012'",
                String.class)).isEqualTo("NO_DATA");
        assertThat(count("SELECT count(*) FROM ops.skip_log WHERE reason_code = 'NO_DATA' AND job_execution_id = ?", je.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM dart.fs_fetch")).isEqualTo(24);
    }

    @Test
    void 요청제한_020_이면_STOPPED_로_멈추고_다음_실행에서_이어간다() {
        status("00000001", "2024", "11012", "CFS", "020");   // 19번째 조합

        var first = launcher.runAndWait("financialStatementJob", RUN);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.STOPPED);
        assertThat(count("SELECT count(*) FROM dart.fs_fetch")).isEqualTo(18);
        assertThat(count("SELECT count(*) FROM ops.skip_log WHERE reason_code = 'QUOTA'")).isEqualTo(1);
        int callsBefore = count("SELECT calls FROM ops.api_quota WHERE provider = 'DART'");

        clearOverrides();
        var second = launcher.runAndWait("financialStatementJob", Map.of());
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("SELECT count(*) FROM dart.fs_fetch WHERE status = 'OK'")).isEqualTo(24);
        assertThat(count("SELECT count(*) FROM dart.fs_raw")).isEqualTo(24 * ROWS_PER_REPORT);
        assertThat(count("SELECT calls FROM ops.api_quota WHERE provider = 'DART'") - callsBefore).isEqualTo(6);  // 남은 6조합만
    }

    @Test
    void 일일_상한에_닿으면_호출하지_않고_STOPPED() {
        jdbc.update("INSERT INTO ops.api_quota (provider, day, calls) VALUES ('DART', (now() AT TIME ZONE 'Asia/Seoul')::date, 14995)");
        var je = launcher.runAndWait("financialStatementJob", RUN);
        assertThat(je.getStatus()).isEqualTo(BatchStatus.STOPPED);
        assertThat(count("SELECT count(*) FROM dart.fs_fetch")).isEqualTo(5);
        assertThat(WM.getAllServeEvents()).hasSize(5);
    }

    @Test
    void 표준화_골든_지표() {   // 원천 fixture → fs_std → 지표 기대값
        launcher.runAndWait("financialStatementJob", RUN);
        var je = launcher.runAndWait("standardizeMetricJob", Map.of());
        assertThat(je.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> std = jdbc.queryForList("""
                SELECT std_code, amount FROM dart.fs_std WHERE corp_code = '00000001' AND period_key = '2025Q2' AND basis <> 'QTR'""")
                .stream().collect(java.util.stream.Collectors.toMap(r -> (String) r.get("std_code"), r -> r.get("amount")));
        assertThat(std).hasSize(11);   // 장기차입금은 fixture 에 없음
        assertThat(std.get("SHORT_BORROWINGS").toString()).isEqualTo("150000");   // 단기차입금 + 유동성장기부채
        assertThat(std.get("REVENUE").toString()).isEqualTo("400000");            // 당기누적
        assertThat(jdbc.queryForObject("SELECT amount FROM dart.fs_std WHERE corp_code = '00000001' AND period_key = '2025Q1' AND std_code = 'REVENUE' AND basis = 'QTR'",
                java.math.BigDecimal.class)).isEqualByComparingTo("400000");
        assertThat(jdbc.queryForObject("SELECT value FROM risk.company_metric WHERE corp_code = '00000001' AND period_key = '2025Q2' AND metric_code = 'DEBT_RATIO'",
                java.math.BigDecimal.class)).isEqualByComparingTo("233.3333");
        assertThat(jdbc.queryForObject("SELECT value FROM risk.company_metric WHERE corp_code = '00000001' AND period_key = '2025Q2' AND metric_code = 'BORROWING_DEP'",
                java.math.BigDecimal.class)).isEqualByComparingTo("23");
        assertThat(jdbc.queryForObject("SELECT status FROM risk.company_metric WHERE corp_code = '00000001' AND period_key = '2025Q2' AND metric_code = 'INTEREST_COVERAGE'",
                String.class)).isEqualTo("ZERO_DENOM");   // 누적이 같아 분기 이자비용 0
        assertThat(jdbc.queryForObject("SELECT components->>'rceptNo' FROM risk.company_metric WHERE corp_code = '00000001' AND period_key = '2025Q2' AND metric_code = 'DEBT_RATIO'",
                String.class)).isEqualTo("20260814000001");   // NFR-04 원천 추적
    }
}
