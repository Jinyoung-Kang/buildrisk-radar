package com.buildrisk.radar.batch;

import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** FR-101 고유번호 Zip → CORPCODE.xml → ref.company. 끝나면 임시 파일을 지움 — /tmp 는 메모리(tmpfs)라 worker 한도를 먹음 (VERIFICATION 137) */
class CorpCodeSyncJobIT extends IntegrationTest {
    @Autowired
    BatchLauncher launcher;

    private static byte[] zip(String xml) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("CORPCODE.xml"));
            z.write(xml.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void 끝나면_임시_파일을_지운다() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><result>"
                + "<list><corp_code>00000901</corp_code><corp_name>임시건설</corp_name><corp_eng_name>Tmp</corp_eng_name>"
                + "<stock_code>000901</stock_code><modify_date>20260101</modify_date></list>"
                + "<list><corp_code>00000902</corp_code><corp_name>임시개발</corp_name><corp_eng_name>Tmp2</corp_eng_name>"
                + "<stock_code> </stock_code><modify_date>20260101</modify_date></list></result>";
        WM.stubFor(get(urlPathEqualTo("/api/corpCode.xml")).willReturn(aResponse().withBody(zip(xml))));
        var je = launcher.runAndWait("corpCodeSyncJob", Map.of("restart", false));
        assertThat(je.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("SELECT count(*) FROM ref.company WHERE corp_code IN ('00000901', '00000902')")).isEqualTo(2);
        Path file = Path.of(je.getExecutionContext().getString("corpCodeXml"));
        assertThat(Files.exists(file)).as(file.toString()).isFalse();
        assertThat(Files.exists(file.getParent())).isFalse();
    }
}
