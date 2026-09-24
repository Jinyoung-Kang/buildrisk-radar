package com.buildrisk.radar.batch;

import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제로 겪은 버그의 회귀 테스트: 처리한 행을 WHERE 로 빼는 Reader 가 ExecutionContext 오프셋(페이지 안 건수)으로
 * 재시작하면 아직 처리하지 않은 행을 건너뛴다 (강제 종료 후 restart 에서 50건 누락 — 850 mod 200).
 */
class CompanyProfileRestartIT extends IntegrationTest {
    @Autowired
    BatchLauncher launcher;

    @Test
    void 요청제한으로_멈춘_뒤_restart_해도_기업개황_누락이_없다() {
        for (int i = 1; i <= 120; i++) {
            String c = String.format("%08d", i);
            jdbc.update("INSERT INTO ref.company (corp_code, corp_name, stock_code) VALUES (?, ?, ?)", c, "회사" + i, c.substring(2));
        }
        WM.stubFor(get(urlPathEqualTo("/api/company.json")).atPriority(10).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withTransformers("response-template")
                .withBody("{\"status\":\"000\",\"message\":\"정상\",\"corp_code\":\"{{request.query.corp_code}}\",\"corp_name\":\"x\",\"corp_cls\":\"K\",\"induty_code\":\"41221\"}")));
        WM.stubFor(get(urlPathEqualTo("/api/company.json")).atPriority(1).withQueryParam("corp_code", equalTo("00000080"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"status\":\"020\",\"message\":\"한도\"}")));

        var first = launcher.runAndWait("companyProfileJob", Map.of("restart", false));
        assertThat(first.getStatus()).isEqualTo(BatchStatus.STOPPED);
        assertThat(count("SELECT count(*) FROM ref.company WHERE profile_fetched_at IS NOT NULL")).isEqualTo(79);

        WM.getStubMappings().stream().filter(s -> s.getPriority() == 1).toList().forEach(WM::removeStub);
        var second = launcher.runAndWait("companyProfileJob", Map.of());
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("SELECT count(*) FROM ref.company WHERE stock_code IS NOT NULL AND profile_fetched_at IS NULL")).isZero();
    }
}
