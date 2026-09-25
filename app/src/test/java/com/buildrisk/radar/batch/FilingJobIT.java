package com.buildrisk.radar.batch;

import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-016 — document.xml(Zip) 수집 → 원문 보관 · 구조화 · 지역 매핑 · 정정/해지 연결 · 파서 버전 올리면 호출 없이 재파싱
 */
class FilingJobIT extends IntegrationTest {
    private static final String DOC = "/api/document.xml";
    @Autowired
    BatchLauncher launcher;

    @BeforeEach
    void seed() throws IOException {
        cleanup();
        jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level) VALUES ('26380', '사하구', '부산광역시 사하구', '26', '부산광역시', 2)");
        jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level) VALUES ('29110', '동구', '광주광역시 동구', '29', '광주광역시', 2)");
        company("00000501", "구조화건설");
        LocalDate d = LocalDate.now().minusDays(3);
        disclosure("20260923800033", "단일판매ㆍ공급계약체결", d, "CONTRACT");
        disclosure("20260801000001", "단일판매ㆍ공급계약체결", d.minusDays(50), "CONTRACT");     // 정정 대상 원 공시 (본문 없음)
        disclosure("20260923900468", "[기재정정]단일판매ㆍ공급계약체결", d, "CONTRACT");
        disclosure("20260722800676", "단일판매ㆍ공급계약해지", d, "CONTRACT");
        disclosure("20260918800184", "타인에대한채무보증결정", d, "GUARANTEE");
        disclosure("20260101000009", "주요사항보고서(유상증자결정)", d, "FUNDING");              // 대상 아님
        stubDoc("20260923800033", "contract_kospi_20260923800033.html");
        stubDoc("20260923900468", "contract_correction_20260923900468.html");
        stubDoc("20260722800676", "termination_20260722800676.html");
        stubDoc("20260918800184", "guarantee_pf_20260918800184.html");
        WM.stubFor(get(urlPathEqualTo(DOC)).withQueryParam("rcept_no", equalTo("20260801000001"))
                .willReturn(aResponse().withBody("{\"status\":\"014\",\"message\":\"파일이 존재하지 않습니다.\"}")));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM dart.filing_doc");
        jdbc.update("DELETE FROM ops.job_request");
        jdbc.update("DELETE FROM ref.region WHERE region_cd IN ('26380', '29110')");
    }

    private void disclosure(String no, String name, LocalDate date, String type) {
        jdbc.update("INSERT INTO dart.disclosure (rcept_no, corp_code, report_nm, rcept_dt, event_type) VALUES (?, '00000501', ?, ?, ?)",
                no, name, date, type);
    }

    private static void stubDoc(String no, String fixture) throws IOException {
        byte[] html;
        try (var in = FilingJobIT.class.getResourceAsStream("/fixtures/dart/filings/" + fixture)) {
            html = in.readAllBytes();
        }
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(no + ".xml"));
            zip.write(html);
            zip.closeEntry();
        }
        WM.stubFor(get(urlPathEqualTo(DOC)).withQueryParam("rcept_no", equalTo(no))
                .willReturn(aResponse().withHeader("Content-Type", "application/zip").withBody(out.toByteArray())));
    }

    @Test
    void 원문을_구조화하고_지역_매핑과_해지를_연결한다() {
        var je = launcher.runAndWait("filingParseJob", Map.of("restart", false));
        assertThat(je.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        WM.verify(5, getRequestedFor(urlPathEqualTo(DOC)));                     // 유상증자 공시는 부르지 않음
        assertThat(count("SELECT count(*) FROM dart.filing_doc WHERE status = 'PARSED'")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM dart.filing_doc WHERE status = 'NO_DOC'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM dart.filing_doc WHERE raw_html IS NOT NULL AND length(raw_sha256) = 64")).isEqualTo(4);

        var c = jdbc.queryForMap("SELECT contract_name, amount, region_cd, region_match FROM dart.contract WHERE rcept_no = '20260923800033'");
        assertThat(c.get("contract_name")).isEqualTo("감천2구역 주택재개발정비사업");
        assertThat(c.get("region_cd")).isEqualTo("26380");                      // '부산광역시 사하구 감천동 …' → 사하구
        assertThat(c.get("region_match")).isEqualTo("SIGUNGU");
        assertThat(jdbc.queryForObject("SELECT region_cd FROM dart.contract WHERE rcept_no = '20260923900468'", String.class))
                .isEqualTo("29110");                                            // '광주광역시 동구 소태동 일원'

        var g = jdbc.queryForMap("SELECT total_balance, pf_amount, pf_lines::text AS lines FROM dart.guarantee WHERE rcept_no = '20260918800184'");
        assertThat(g.get("total_balance").toString()).isEqualTo("2340556580911");
        assertThat(g.get("lines").toString()).contains("기타 PF Loan");

        // 재실행: 받은 공시는 다시 부르지 않음
        WM.resetRequests();
        assertThat(launcher.runAndWait("filingParseJob", Map.of("restart", false)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        WM.verify(0, getRequestedFor(urlPathEqualTo(DOC)));

        // 파서 버전이 오르면(여기선 원장 버전을 내려 흉내) 호출 없이 원문으로 다시 파싱
        jdbc.update("UPDATE dart.filing_doc SET parser_version = 0");
        jdbc.update("DELETE FROM dart.contract");
        assertThat(launcher.runAndWait("filingParseJob", Map.of("restart", false)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        WM.verify(0, getRequestedFor(urlPathEqualTo(DOC)));
        assertThat(count("SELECT count(*) FROM dart.contract")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM dart.filing_doc WHERE parser_version = 0 AND raw_html IS NOT NULL")).isZero();   // 원문 없는 NO_DOC 은 제외
    }

    @Test
    void 해지_공시는_같은_이름의_계약을_현재에서_뺀다() {
        launcher.runAndWait("filingParseJob", Map.of("restart", false));
        // 해지 공시의 원 계약(2024-05-31, '부산 하단1구역 재건축정비사업')을 흉내 내 넣고 연결 단계를 다시 돌림
        jdbc.update("INSERT INTO dart.disclosure (rcept_no, corp_code, report_nm, rcept_dt, event_type) VALUES ('20240531000637', '00000501', '단일판매ㆍ공급계약체결', '2024-05-31', 'CONTRACT')");
        jdbc.update("INSERT INTO dart.filing_doc (rcept_no, corp_code, kind, status, parser_version) VALUES ('20240531000637', '00000501', 'CONTRACT', 'PARSED', 1)");
        jdbc.update("""
                INSERT INTO dart.contract (rcept_no, corp_code, rcept_dt, contract_name, name_key, amount, region_match)
                VALUES ('20240531000637', '00000501', '2024-05-31', '부산 하단1구역 재건축정비사업', '부산하단1구역재건축정비사업', 116787990000, 'NONE')""");
        launcher.runAndWait("filingParseJob", Map.of("restart", false, "days", 1));
        assertThat(jdbc.queryForObject("SELECT terminated_by FROM dart.contract WHERE rcept_no = '20240531000637'", String.class))
                .isEqualTo("20260722800676");
    }
}
