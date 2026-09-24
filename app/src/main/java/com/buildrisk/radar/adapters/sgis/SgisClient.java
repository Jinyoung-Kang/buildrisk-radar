package com.buildrisk.radar.adapters.sgis;

import com.buildrisk.radar.adapters.common.ApiKeyMissingException;
import com.buildrisk.radar.adapters.common.HttpSupport;
import com.buildrisk.radar.adapters.common.Json;
import com.buildrisk.radar.adapters.common.UpstreamException;
import com.buildrisk.radar.common.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** SGIS OpenAPI3 — 인증(D9) · 총조사 주요지표(D11). 일일 50,000회 제한(D7). */
@Component
public class SgisClient {
    public static final String PROVIDER = "SGIS";

    public record Row(String admCd, String admNm, BigDecimal households, BigDecimal population) {}

    /** 행정구역 경계 한 건 — 좌표계 UTM-K (EPSG:5179) */
    public record Boundary(String admCd, String admNm, String geometryJson) {}

    private final AppProperties.Sgis cfg;
    private final RestClient http;
    private final ObjectMapper mapper;
    private String token;
    private long tokenExpiresAt;

    public SgisClient(AppProperties props, ObjectMapper mapper) {
        this.cfg = props.sgis();
        this.http = HttpSupport.client(cfg.baseUrl(), Duration.ofSeconds(60));
        this.mapper = mapper;
    }

    /** adm_cd(시도 2자리) 아래 한 단계(low_search=1) 주요지표 */
    public List<Row> population(int year, String admCd) {
        JsonNode n = get("/stats/population.json", year, admCd, true);
        List<Row> rows = new ArrayList<>();
        for (JsonNode r : n.path("result").values()) {
            rows.add(new Row(Json.text(r, "adm_cd"), Json.text(r, "adm_nm"), Json.amount(r, "tot_family"),
                    Json.amount(r, "tot_ppltn")));
        }
        return rows;
    }

    /** boundary/hadmarea.geojson — 시도(adm_cd 2자리) 아래 시군구 경계 */
    public List<Boundary> boundary(int year, String admCd) {
        JsonNode n = get("/boundary/hadmarea.geojson", year, admCd, true);
        List<Boundary> out = new ArrayList<>();
        for (JsonNode f : n.path("features").values()) {
            JsonNode p = f.path("properties");
            out.add(new Boundary(Json.text(p, "adm_cd"), Json.text(p, "adm_nm"), f.path("geometry").toString()));
        }
        return out;
    }

    public boolean configured() {
        return !HttpSupport.blank(cfg.consumerKey()) && !HttpSupport.blank(cfg.consumerSecret());
    }

    private JsonNode get(String path, int year, String admCd, boolean retryAuth) {
        String tok = token();
        String body = HttpSupport.retry(3, () -> {
            try {
                return http.get().uri(u -> u.path(path).queryParam("accessToken", tok).queryParam("year", year)
                        .queryParam("adm_cd", admCd).queryParam("low_search", 1).build()).retrieve().body(String.class);
            } catch (RestClientException e) {
                throw new UpstreamException(PROVIDER, e.getMessage(), e);
            }
        });
        JsonNode n = mapper.readTree(body);
        String err = Json.text(n, "errCd");
        if ("-401".equals(err) && retryAuth) {
            synchronized (this) { token = null; }
            return get(path, year, admCd, false);
        }
        if ("-100".equals(err)) return mapper.createObjectNode();        // 검색결과 없음
        if (err != null && !"0".equals(err)) throw new UpstreamException(PROVIDER, err + " " + Json.text(n, "errMsg"));
        return n;
    }

    private synchronized String token() {
        if (HttpSupport.blank(cfg.consumerKey()) || HttpSupport.blank(cfg.consumerSecret())) {
            throw new ApiKeyMissingException("SGIS_CONSUMER_KEY / SGIS_CONSUMER_SECRET");
        }
        if (token != null && System.currentTimeMillis() < tokenExpiresAt - 60_000) return token;
        String body = HttpSupport.retry(3, () -> {
            try {
                return http.get().uri(u -> u.path("/auth/authentication.json")
                        .queryParam("consumer_key", cfg.consumerKey())
                        .queryParam("consumer_secret", cfg.consumerSecret()).build()).retrieve().body(String.class);
            } catch (RestClientException e) {
                throw new UpstreamException(PROVIDER, e.getMessage(), e);
            }
        });
        JsonNode n = mapper.readTree(body);
        if (!"0".equals(Json.text(n, "errCd"))) {
            throw new UpstreamException(PROVIDER, "인증 실패 " + Json.text(n, "errCd") + " " + Json.text(n, "errMsg"));
        }
        token = Json.text(n.path("result"), "accessToken");
        String timeout = Json.text(n.path("result"), "accessTimeout");
        tokenExpiresAt = timeout == null ? System.currentTimeMillis() + 3_600_000 : Long.parseLong(timeout);
        return token;
    }
}
