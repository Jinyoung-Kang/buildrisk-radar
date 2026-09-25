package com.buildrisk.radar.adapters.datagokr;

import com.buildrisk.radar.adapters.common.Json;
import com.buildrisk.radar.adapters.common.UpstreamException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 금융위원회 주식시세 (GetStockSecuritiesInfoService_V2/getStockPriceInfo_V2, JSON).
 * endBasDt 는 '보다 작은'(미포함) 조건이고, likeSrtnCd 는 포함 검색이라 단축코드가 정확히 같은 행만 남깁니다.
 * 데이터는 영업일 다음 날 오후에 갱신됩니다(활용가이드 0.2).
 */
@Component
public class StockPriceClient {
    public static final String PROVIDER = "FSC_STOCK";
    private static final String PATH = "/1160100/GetStockSecuritiesInfoService_V2/getStockPriceInfo_V2";
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int PAGE = 1000;

    public record Daily(LocalDate basDt, String srtnCd, BigDecimal clpr, BigDecimal mkp, BigDecimal hipr, BigDecimal lopr,
                        Long trqu, BigDecimal fltRt, Long lstgStCnt, BigDecimal mrktTotAmt) {}

    record Page(int totalCount, List<Daily> items) {}

    private final DataGoKrGateway gateway;
    private final ObjectMapper mapper;

    public StockPriceClient(DataGoKrGateway gateway, ObjectMapper mapper) {
        this.gateway = gateway;
        this.mapper = mapper;
    }

    /** [from, toExclusive) 일별 시세 (기준일 오름차순) */
    public List<Daily> daily(String stockCode, LocalDate from, LocalDate toExclusive) {
        List<Daily> out = new ArrayList<>();
        int page = 1, seen = 0;
        Page p;
        do {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("resultType", "json");
            q.put("likeSrtnCd", stockCode);
            q.put("beginBasDt", from.format(YMD));
            q.put("endBasDt", toExclusive.format(YMD));
            q.put("numOfRows", PAGE);
            q.put("pageNo", page);
            p = parse(mapper, gateway.get(PROVIDER, "getStockPriceInfo_V2", PATH, q));
            seen += p.items().size();
            p.items().stream().filter(d -> stockCode.equals(d.srtnCd())).forEach(out::add);
            page++;
        } while (!p.items().isEmpty() && seen < p.totalCount());
        out.sort(java.util.Comparator.comparing(Daily::basDt));
        return out;
    }

    static Page parse(ObjectMapper mapper, String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (RuntimeException e) {
            throw new UpstreamException(PROVIDER, "JSON 이 아닌 응답: " + body.substring(0, Math.min(200, body.length())));
        }
        JsonNode res = root.path("response");
        String code = Json.text(res.path("header"), "resultCode");
        if (code != null && !code.equals("00")) {
            throw new UpstreamException(PROVIDER, code + " " + Json.text(res.path("header"), "resultMsg"));
        }
        JsonNode body0 = res.path("body");
        JsonNode items = body0.path("items").path("item");
        List<Daily> out = new ArrayList<>();
        if (items.isArray()) items.values().forEach(i -> out.add(row(i)));
        else if (items.isObject()) out.add(row(items));                 // 1건이면 배열이 아닐 수 있음
        return new Page(body0.path("totalCount").asInt(0), out);
    }

    private static Daily row(JsonNode i) {
        return new Daily(LocalDate.parse(Json.text(i, "basDt"), YMD), Json.text(i, "srtnCd"), Json.amount(i, "clpr"),
                Json.amount(i, "mkp"), Json.amount(i, "hipr"), Json.amount(i, "lopr"), longOrNull(i, "trqu"),
                Json.amount(i, "fltRt"), longOrNull(i, "lstgStCnt"), Json.amount(i, "mrktTotAmt"));
    }

    private static Long longOrNull(JsonNode i, String f) {
        BigDecimal v = Json.amount(i, f);
        return v == null ? null : v.longValueExact();
    }
}
