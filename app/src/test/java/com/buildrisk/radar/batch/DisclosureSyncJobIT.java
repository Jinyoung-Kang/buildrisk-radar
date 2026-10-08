package com.buildrisk.radar.batch;

import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** 공시 수집 (FR-301) — 행마다 수집한 배치 실행을 남김 (NFR-04 추적성, 근거 계보 ADR-027 이 이 값으로 수집 실행을 보여 줌) */
class DisclosureSyncJobIT extends IntegrationTest {
    @Autowired
    BatchLauncher launcher;

    @Test
    void 공시_행에_수집한_배치_실행_ID_가_남는다() {
        company("00000951", "공시건설");
        WM.stubFor(get(urlPathEqualTo("/api/list.json")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                {"status":"000","message":"정상","page_no":1,"page_count":100,"total_count":1,"total_page":1,
                 "list":[{"corp_code":"00000951","corp_name":"공시건설","stock_code":"000951","corp_cls":"Y",
                          "report_nm":"기타시장안내(관리종목지정우려종목)","rcept_no":"20261001000951","flr_nm":"유가증권시장본부",
                          "rcept_dt":"20261001","rm":"유"}]}""")));
        var je = launcher.runAndWait("disclosureSyncJob", Map.of());
        assertThat(je.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        var row = jdbc.queryForMap("SELECT event_type, job_execution_id FROM dart.disclosure WHERE rcept_no = '20261001000951'");
        assertThat(row.get("event_type")).isEqualTo("SUSPENSION");
        assertThat(row.get("job_execution_id")).isEqualTo(je.getId());
    }
}
