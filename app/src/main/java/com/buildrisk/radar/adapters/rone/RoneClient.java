package com.buildrisk.radar.adapters.rone;

import com.buildrisk.radar.adapters.common.ApiKeyMissingException;
import com.buildrisk.radar.adapters.common.HttpSupport;
import com.buildrisk.radar.adapters.common.Json;
import com.buildrisk.radar.adapters.common.Throttle;
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

/**
 * 한국부동산원 R-ONE 부동산통계 Open API (E21). CORS 미지원이라 서버에서만 호출합니다.
 * KEY 없이 부르면 샘플 5건만 오므로 키가 없으면 바로 실패시킵니다.
 */
@Component
public class RoneClient {
    public static final String PROVIDER = "RONE";

    public record Row(String statblId, String period, String clsId, String clsNm, String clsFullNm, String itmId,
                      BigDecimal value, String unit) {}

    public record Page(int total, List<Row> rows) {}

    private final AppProperties.Rone cfg;
    private final RestClient http;
    private final ObjectMapper mapper;
    private final Throttle throttle;

    public RoneClient(AppProperties props, ObjectMapper mapper) {
        this.cfg = props.rone();
        this.http = HttpSupport.client(cfg.baseUrl(), Duration.ofSeconds(60));
        this.mapper = mapper;
        this.throttle = new Throttle(cfg.minIntervalMs());
    }

    /** SttsApiTblData.do — 한 시점(WRTTIME_IDTFR_ID)의 한 페이지 */
    public Page data(String statblId, String cycle, String period, int pIndex, int pSize) {
        if (HttpSupport.blank(cfg.apiKey())) throw new ApiKeyMissingException("REB_API_KEY");
        String body = HttpSupport.retry(3, () -> {
            throttle.acquire();
            try {
                return http.get().uri(u -> u.path("/r-one/openapi/SttsApiTblData.do")
                        .queryParam("KEY", cfg.apiKey()).queryParam("Type", "json")
                        .queryParam("pIndex", pIndex).queryParam("pSize", pSize)
                        .queryParam("STATBL_ID", statblId).queryParam("DTACYCLE_CD", cycle)
                        .queryParam("WRTTIME_IDTFR_ID", period).build()).retrieve().body(String.class);
            } catch (RestClientException e) {
                throw new UpstreamException(PROVIDER, e.getMessage(), e);
            }
        });
        JsonNode root = mapper.readTree(body == null ? "{}" : body);
        JsonNode sections = root.path("SttsApiTblData");
        if (!sections.isArray()) {
            JsonNode result = root.path("RESULT");
            String code = Json.text(result, "CODE");
            if ("INFO-200".equals(code)) return new Page(0, List.of());   // 해당 데이터 없음
            throw new UpstreamException(PROVIDER, code + " " + Json.text(result, "MESSAGE"));
        }
        int total = 0;
        List<Row> rows = new ArrayList<>();
        for (JsonNode sec : sections.values()) {
            for (JsonNode h : sec.path("head").values()) {
                if (h.has("list_total_count")) total = h.path("list_total_count").asInt();
                JsonNode res = h.path("RESULT");
                String code = Json.text(res, "CODE");
                if (code != null && !code.equals("INFO-000")) {
                    throw new UpstreamException(PROVIDER, code + " " + Json.text(res, "MESSAGE"));
                }
            }
            for (JsonNode r : sec.path("row").values()) {
                rows.add(new Row(Json.text(r, "STATBL_ID"), Json.text(r, "WRTTIME_IDTFR_ID"), Json.text(r, "CLS_ID"),
                        Json.text(r, "CLS_NM"), Json.text(r, "CLS_FULLNM"), Json.text(r, "ITM_ID"),
                        Json.amount(r, "DTA_VAL"), Json.text(r, "UI_NM")));
            }
        }
        return new Page(total, rows);
    }
}
