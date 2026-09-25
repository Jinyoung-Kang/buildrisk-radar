package com.buildrisk.radar.adapters.vworld;

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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * V-World 데이터 API GetFeature — 시군구 경계(LT_C_ADSIGG_INFO, EPSG:4326).
 * 키는 발급 시 등록한 서비스 URL(domain)과 함께 보내야 합니다.
 */
@Component
public class VworldClient {
    public static final String PROVIDER = "VWORLD";
    /** 남한 전역 BBOX (geomFilter 필수) */
    private static final String KOREA_BOX = "BOX(124.0,33.0,132.0,38.7)";

    public record Feature(String sigCd, String korName, String fullName, String geometryJson) {}

    public record Page(int page, int totalPages, int totalRecords, List<Feature> features) {}

    private final AppProperties.Vworld cfg;
    private final RestClient http;
    private final ObjectMapper mapper;

    private final com.buildrisk.radar.adapters.common.ExternalApiMetrics metrics;

    public VworldClient(AppProperties props, ObjectMapper mapper, com.buildrisk.radar.adapters.common.ExternalApiMetrics metrics) {
        this.metrics = metrics;
        this.cfg = props.vworld();
        this.http = HttpSupport.client(cfg.baseUrl(), Duration.ofSeconds(120));
        this.mapper = mapper;
    }

    public boolean configured() { return !HttpSupport.blank(cfg.apiKey()); }

    public Page page(int page, int size) {
        if (HttpSupport.blank(cfg.apiKey())) throw new ApiKeyMissingException("VWORLD_API_KEY");
        String body = HttpSupport.retry(3, () -> metrics.time(PROVIDER, "GetFeature", () -> {
            try {
                return http.get().uri(u -> u.path("/req/data").queryParam("service", "data")
                        .queryParam("version", "2.0").queryParam("request", "GetFeature")
                        .queryParam("data", cfg.dataId()).queryParam("key", cfg.apiKey())
                        .queryParam("domain", cfg.domain()).queryParam("format", "json")
                        .queryParam("geometry", "true").queryParam("attribute", "true")
                        .queryParam("crs", "EPSG:4326").queryParam("geomFilter", KOREA_BOX)
                        .queryParam("size", size).queryParam("page", page).build()).retrieve().body(String.class);
            } catch (RestClientException e) {
                throw new UpstreamException(PROVIDER, e.getMessage(), e);
            }
        }));
        JsonNode res = mapper.readTree(body).path("response");
        String status = Json.text(res, "status");
        if ("NOT_FOUND".equals(status)) return new Page(page, 0, 0, List.of());
        if (!"OK".equals(status)) {
            JsonNode err = res.path("error");
            throw new UpstreamException(PROVIDER, status + " " + Json.text(err, "code") + " " + Json.text(err, "text"));
        }
        List<Feature> out = new ArrayList<>();
        for (JsonNode f : res.path("result").path("featureCollection").path("features").values()) {
            JsonNode p = f.path("properties");
            out.add(new Feature(Json.text(p, "sig_cd"), Json.text(p, "sig_kor_nm"), Json.text(p, "full_nm"),
                    f.path("geometry").toString()));
        }
        return new Page(res.path("page").path("current").asInt(page), res.path("page").path("total").asInt(1),
                res.path("record").path("total").asInt(out.size()), out);
    }
}
