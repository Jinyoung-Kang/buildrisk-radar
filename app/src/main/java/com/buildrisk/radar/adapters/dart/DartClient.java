package com.buildrisk.radar.adapters.dart;

import com.buildrisk.radar.adapters.common.ApiKeyMissingException;
import com.buildrisk.radar.adapters.common.ApiQuotaService;
import com.buildrisk.radar.adapters.common.HttpSupport;
import com.buildrisk.radar.adapters.common.Json;
import com.buildrisk.radar.adapters.common.Throttle;
import com.buildrisk.radar.adapters.common.UpstreamException;
import com.buildrisk.radar.adapters.dart.DartModels.CompanyProfile;
import com.buildrisk.radar.adapters.dart.DartModels.Disclosure;
import com.buildrisk.radar.adapters.dart.DartModels.DisclosurePage;
import com.buildrisk.radar.adapters.dart.DartModels.FsResponse;
import com.buildrisk.radar.adapters.dart.DartModels.FsRow;
import com.buildrisk.radar.common.AppProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Open DART 어댑터. 모든 호출은 ① 일일 상한 확인(ops.api_quota) ② 호출 간격(기본 200ms) ③ 상태 코드 분류를 거칩니다.
 *   000 정상 · 013 데이터 없음 · 020 요청 제한 → {@link DartQuotaExceededException} · 010/011/… → {@link DartApiException}
 */
@Component
public class DartClient {
    public static final String PROVIDER = "DART";
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private final AppProperties.Dart cfg;
    private final RestClient http;
    private final ObjectMapper mapper;
    private final ApiQuotaService quota;
    private final Throttle throttle;

    public DartClient(AppProperties props, ObjectMapper mapper, ApiQuotaService quota) {
        this.cfg = props.dart();
        this.http = HttpSupport.client(cfg.baseUrl(), Duration.ofSeconds(60));
        this.mapper = mapper;
        this.quota = quota;
        this.throttle = new Throttle(cfg.minIntervalMs());
    }

    public boolean configured() { return !HttpSupport.blank(cfg.apiKey()); }

    /** corpCode.xml(Zip) 을 받아 CORPCODE.xml 을 dir 에 풀고 경로를 돌려줍니다 (FR-101). */
    public Path downloadCorpCodeXml(Path dir) {
        byte[] body = call("/api/corpCode.xml", u -> u);
        if (body.length < 4 || body[0] != 'P' || body[1] != 'K') {
            // Zip 이 아니면 오류 응답(XML/JSON)
            String text = new String(body, StandardCharsets.UTF_8);
            String status = between(text, "<status>", "</status>");
            String message = between(text, "<message>", "</message>");
            if (status == null) {
                JsonNode n = mapper.readTree(text);
                status = Json.text(n, "status");
                message = Json.text(n, "message");
            }
            checkStatus(status, message);
            throw new UpstreamException(PROVIDER, "corpCode.xml 응답이 Zip 이 아닙니다.");
        }
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(body))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().toLowerCase().endsWith(".xml")) {
                    Files.createDirectories(dir);
                    Path out = dir.resolve("CORPCODE.xml");
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                    return out;
                }
            }
        } catch (IOException ex) {
            throw new UpstreamException(PROVIDER, "corpCode Zip 해제 실패: " + ex.getMessage(), ex);
        }
        throw new UpstreamException(PROVIDER, "corpCode Zip 안에 XML 이 없습니다.");
    }

    /** company.json — 013 이면 null */
    public CompanyProfile company(String corpCode) {
        JsonNode n = json("/api/company.json", u -> u.queryParam("corp_code", corpCode));
        if (DartStatus.NO_DATA.equals(Json.text(n, "status"))) return null;
        return new CompanyProfile(Json.text(n, "corp_code"), Json.text(n, "corp_name"), Json.text(n, "corp_name_eng"),
                Json.text(n, "stock_code"), Json.text(n, "corp_cls"), Json.text(n, "induty_code"),
                Json.text(n, "adres"), Json.text(n, "acc_mt"), Json.text(n, "ceo_nm"), Json.text(n, "hm_url"));
    }

    /** fnlttSinglAcntAll.json — 단일회사 전체 재무제표 (E16). 자본변동표(SCE)는 저장하지 않습니다. */
    public FsResponse financialStatements(String corpCode, String bsnsYear, String reprtCode, String fsDiv) {
        JsonNode n = json("/api/fnlttSinglAcntAll.json", u -> u.queryParam("corp_code", corpCode)
                .queryParam("bsns_year", bsnsYear).queryParam("reprt_code", reprtCode).queryParam("fs_div", fsDiv));
        if (DartStatus.NO_DATA.equals(Json.text(n, "status"))) return new FsResponse(fsDiv, true, List.of());
        List<FsRow> rows = parseFsRows(n);
        return new FsResponse(fsDiv, rows.isEmpty(), rows);
    }

    /** fnlttSinglAcntAll 응답 list → 행 (골든 테스트도 같은 파서를 씀). 자본변동표(SCE)는 제외 */
    public static List<FsRow> parseFsRows(JsonNode n) {
        List<FsRow> rows = new ArrayList<>();
        int line = 0;
        for (JsonNode r : n.path("list").values()) {
            line++;
            String sj = Json.text(r, "sj_div");
            if (sj == null || sj.equals("SCE")) continue;
            String ord = Json.text(r, "ord");
            rows.add(new FsRow(line, Json.text(r, "rcept_no"), sj, ord == null ? null : Integer.valueOf(ord),
                    Json.text(r, "account_id"), Json.text(r, "account_nm"), Json.text(r, "account_detail"),
                    Json.amount(r, "thstrm_amount"), Json.amount(r, "thstrm_add_amount"),
                    Json.amount(r, "frmtrm_amount"), Json.text(r, "currency")));
        }
        return rows;
    }

    /** list.json — 공시검색 (E17, page_count 최대 100) */
    public DisclosurePage disclosures(String corpCode, LocalDate from, LocalDate to, int pageNo) {
        JsonNode n = json("/api/list.json", u -> u.queryParam("corp_code", corpCode)
                .queryParam("bgn_de", from.format(YMD)).queryParam("end_de", to.format(YMD))
                .queryParam("page_no", pageNo).queryParam("page_count", 100));
        if (DartStatus.NO_DATA.equals(Json.text(n, "status"))) return new DisclosurePage(pageNo, 0, 0, List.of());
        List<Disclosure> items = new ArrayList<>();
        for (JsonNode r : n.path("list").values()) {
            items.add(new Disclosure(Json.text(r, "rcept_no"), Json.text(r, "corp_code"), Json.text(r, "corp_name"),
                    Json.text(r, "report_nm"), LocalDate.parse(Json.text(r, "rcept_dt"), YMD),
                    Json.text(r, "flr_nm"), Json.text(r, "rm")));
        }
        return new DisclosurePage(n.path("page_no").asInt(pageNo), n.path("total_page").asInt(1),
                n.path("total_count").asInt(items.size()), items);
    }

    // ---------------------------------------------------------------------

    private JsonNode json(String path, java.util.function.UnaryOperator<org.springframework.web.util.UriBuilder> q) {
        byte[] body = call(path, q);
        JsonNode n;
        try {
            n = mapper.readTree(body);
        } catch (RuntimeException e) {
            throw new UpstreamException(PROVIDER, path + " 응답이 JSON 이 아닙니다.", e);
        }
        checkStatus(Json.text(n, "status"), Json.text(n, "message"));
        return n;
    }

    private void checkStatus(String status, String message) {
        if (status == null || DartStatus.OK.equals(status) || DartStatus.NO_DATA.equals(status)) return;
        if (DartStatus.QUOTA.equals(status)) throw new DartQuotaExceededException("DART 020 요청 제한: " + message, true);
        if (DartStatus.fatal(status)) throw new DartApiException(status, message);
        throw new UpstreamException(PROVIDER, "status " + status + " " + message);
    }

    private byte[] call(String path, java.util.function.UnaryOperator<org.springframework.web.util.UriBuilder> q) {
        if (!configured()) throw new ApiKeyMissingException("DART_API_KEY");
        int used = quota.used(PROVIDER);
        if (used >= cfg.dailyCallLimit()) {
            throw new DartQuotaExceededException("오늘 DART 호출 " + used + "건 — 일일 상한 " + cfg.dailyCallLimit()
                    + "건에 도달했습니다 (NFR-03).", false);
        }
        throttle.acquire();
        quota.increment(PROVIDER);
        try {
            return http.get().uri(u -> q.apply(u.path(path).queryParam("crtfc_key", cfg.apiKey())).build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new UpstreamException(PROVIDER, path + " HTTP " + res.getStatusCode().value());
                    })
                    .body(byte[].class);
        } catch (ResourceAccessException | RestClientResponseException e) {
            throw new UpstreamException(PROVIDER, path + " " + e.getMessage(), e);
        }
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        if (i < 0) return null;
        int j = s.indexOf(b, i + a.length());
        return j < 0 ? null : s.substring(i + a.length(), j).trim();
    }
}
