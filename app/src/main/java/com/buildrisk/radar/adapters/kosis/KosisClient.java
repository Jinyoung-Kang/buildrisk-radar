package com.buildrisk.radar.adapters.kosis;

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
import java.util.Map;

/** KOSIS 공유서비스 통계자료 (statisticsParameterData.do, E23). */
@Component
public class KosisClient {
    public static final String PROVIDER = "KOSIS";

    /** 한 행: 분류 코드·이름은 C1…Cn 순서 */
    public record Row(String prdDe, List<String> classCodes, List<String> classNames, String itmId,
                      BigDecimal value, String raw, String unit) {}

    private final AppProperties.Kosis cfg;
    private final RestClient http;
    private final ObjectMapper mapper;

    public KosisClient(AppProperties props, ObjectMapper mapper) {
        this.cfg = props.kosis();
        this.http = HttpSupport.client(cfg.baseUrl(), Duration.ofSeconds(90));
        this.mapper = mapper;
    }

    public List<Row> data(String orgId, String tblId, String itmId, Map<String, String> objL, String prdSe,
                          String startPrdDe, String endPrdDe) {
        if (HttpSupport.blank(cfg.apiKey())) throw new ApiKeyMissingException("KOSIS_API_KEY");
        String body = HttpSupport.retry(3, () -> {
            try {
                return http.get().uri(u -> {
                    u.path("/openapi/Param/statisticsParameterData.do")
                            .queryParam("method", "getList").queryParam("apiKey", cfg.apiKey())
                            .queryParam("orgId", orgId).queryParam("tblId", tblId).queryParam("itmId", itmId)
                            .queryParam("prdSe", prdSe).queryParam("startPrdDe", startPrdDe)
                            .queryParam("endPrdDe", endPrdDe).queryParam("format", "json").queryParam("jsonVD", "Y");
                    objL.forEach(u::queryParam);
                    return u.build();
                }).retrieve().body(String.class);
            } catch (RestClientException e) {
                throw new UpstreamException(PROVIDER, e.getMessage(), e);
            }
        });
        JsonNode root = mapper.readTree(body == null ? "[]" : body);
        if (root.isObject()) {
            String err = Json.text(root, "err");
            if ("30".equals(err)) return List.of();          // 조회 결과 없음
            throw new UpstreamException(PROVIDER, "err " + err + " " + Json.text(root, "errMsg"));
        }
        List<Row> rows = new ArrayList<>();
        for (JsonNode r : root.values()) {
            List<String> codes = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                String c = Json.text(r, "C" + i);
                if (c == null) break;
                codes.add(c);
                names.add(Json.text(r, "C" + i + "_NM"));
            }
            String raw = Json.text(r, "DT");
            rows.add(new Row(Json.text(r, "PRD_DE"), codes, names, Json.text(r, "ITM_ID"), Json.amount(raw), raw,
                    Json.text(r, "UNIT_NM")));
        }
        return rows;
    }
}
