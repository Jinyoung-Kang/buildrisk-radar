package com.buildrisk.radar.adapters.applyhome;

import com.buildrisk.radar.adapters.common.ApiKeyMissingException;
import com.buildrisk.radar.adapters.common.ApiKeyRejectedException;
import com.buildrisk.radar.adapters.common.ApiQuotaService;
import com.buildrisk.radar.adapters.common.ExternalApiMetrics;
import com.buildrisk.radar.adapters.common.HttpSupport;
import com.buildrisk.radar.adapters.common.Json;
import com.buildrisk.radar.adapters.common.QuotaExceededException;
import com.buildrisk.radar.adapters.common.Throttle;
import com.buildrisk.radar.adapters.common.UpstreamException;
import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.presale.domain.PresaleModels.Flags;
import com.buildrisk.radar.presale.domain.PresaleModels.Model;
import com.buildrisk.radar.presale.domain.PresaleModels.Notice;
import com.buildrisk.radar.presale.domain.PresaleModels.Page;
import com.buildrisk.radar.presale.domain.PresaleModels.Remainder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 한국부동산원 청약홈 분양정보 조회 서비스 (api.odcloud.kr/api/ApplyhomeInfoDetailSvc/v1, JSON) — ADR-032.
 * 공공데이터포털 인증키를 그대로 씀(2026-10-19 스모크). 응답 {page, perPage, totalCount, currentCount, matchCount, data[]},
 * 조건은 cond[필드::연산자]. 날짜는 'YYYY-MM-DD' 문자열(실측 2,360건 모두) — 다른 형식(YYYYMMDD)도 받고 그 밖은 null.
 * 호출 순서는 DataGoKrGateway 와 같음: 키 없음 → 자체 일일 상한 → 재시도(일시 오류만) · 계측 · 간격 · 호출 수 기록 → 429 · 401/403.
 * 오류 본문은 {"code": -4, "msg": "등록되지 않은 인증키 입니다."} 모양(실측 401).
 */
@Component
public class ApplyhomeClient {
    public static final String PROVIDER = "APPLYHOME";
    private static final String BASE = "/ApplyhomeInfoDetailSvc/v1/";
    private static final int PAGE = 500;
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    private final AppProperties.Applyhome cfg;
    private final RestClient http;
    private final ApiQuotaService quota;
    private final ExternalApiMetrics metrics;
    private final Throttle throttle;
    private final ObjectMapper mapper;

    public ApplyhomeClient(AppProperties props, ApiQuotaService quota, ExternalApiMetrics metrics, ObjectMapper mapper) {
        this.cfg = props.applyhome();
        this.http = HttpSupport.client(cfg.baseUrl(), Duration.ofSeconds(60));
        this.quota = quota;
        this.metrics = metrics;
        this.mapper = mapper;
        this.throttle = new Throttle(cfg.minIntervalMs());
    }

    public boolean configured() { return !HttpSupport.blank(cfg.serviceKey()); }

    public int dailyLimit() { return cfg.dailyCallLimit(); }

    /** APT 분양 공고 — 모집공고일 [from, toExclusive) 전체 쪽 */
    public List<Notice> notices(LocalDate from, LocalDate toExclusive) {
        return all("getAPTLttotPblancDetail", window(from, toExclusive), ApplyhomeClient::parseNotices, from + "~" + toExclusive);
    }

    /** 잔여세대(무순위 · 재공급) 공고 — 모집공고일 [from, toExclusive) */
    public List<Remainder> remainders(LocalDate from, LocalDate toExclusive) {
        return all("getRemndrLttotPblancDetail", window(from, toExclusive), ApplyhomeClient::parseRemainders, from + "~" + toExclusive);
    }

    /** 공고 하나의 주택형 */
    public List<Model> models(String houseManageNo, String pblancNo) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("cond[HOUSE_MANAGE_NO::EQ]", houseManageNo);
        q.put("cond[PBLANC_NO::EQ]", pblancNo);
        return all("getAPTLttotPblancMdl", q, ApplyhomeClient::parseModels, houseManageNo + "/" + pblancNo);
    }

    private static Map<String, Object> window(LocalDate from, LocalDate toExclusive) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("cond[RCRIT_PBLANC_DE::GTE]", from.toString());
        q.put("cond[RCRIT_PBLANC_DE::LT]", toExclusive.toString());
        return q;
    }

    private <T> List<T> all(String op, Map<String, Object> cond, BiFunction<ObjectMapper, String, Page<T>> parser, String what) {
        List<T> out = new ArrayList<>();
        int page = 1;
        Page<T> p;
        do {
            Map<String, Object> q = new LinkedHashMap<>(cond);
            q.put("page", page);
            q.put("perPage", PAGE);
            p = parser.apply(mapper, get(op, q));
            out.addAll(p.items());
            page++;
        } while (!p.items().isEmpty() && out.size() < p.matchCount());
        // 중간 쪽이 빈 정상 응답이면 일부만 받은 것 — 그 달을 일부로 바꾸면 사라지지 않은 공고에 removed_at 이 찍힘 (VERIFICATION 128 과 같은 보호)
        if (out.size() < p.matchCount()) {
            throw new UpstreamException(PROVIDER, op + " " + what + ": " + p.matchCount() + "건 중 " + out.size() + "건만 받음 — 다음 실행에서 다시");
        }
        return out;
    }

    String get(String op, Map<String, Object> params) {
        if (!configured()) throw new ApiKeyMissingException("DATA_GO_KR_KEY");
        int used = quota.used(PROVIDER);
        if (used >= cfg.dailyCallLimit()) {
            throw new QuotaExceededException(PROVIDER + " 오늘 호출 " + used + "건 — 설정한 일일 상한(" + cfg.dailyCallLimit()
                    + ")에 도달해 멈춥니다. 내일 다시 실행하면 이어서 수집합니다.", false);
        }
        return HttpSupport.retry(3, () -> metrics.time(PROVIDER, op, () -> {
            throttle.acquire();
            quota.increment(PROVIDER);
            String body;
            try {
                body = http.get().uri(u -> {
                            u.path(BASE + op);
                            params.forEach(u::queryParam);
                            return u.queryParam("serviceKey", "{serviceKey}").build(cfg.serviceKey());
                        })
                        .retrieve()
                        .onStatus(s -> s.value() == 429, (req, res) -> {
                            throw new QuotaExceededException(PROVIDER + " 트래픽 초과(HTTP 429)", true);
                        })
                        .onStatus(s -> s.value() == 401 || s.value() == 403, (req, res) -> {
                            throw new ApiKeyRejectedException(PROVIDER, "인증 실패(HTTP " + res.getStatusCode().value()
                                    + ") — 공공데이터포털에서 '청약홈 분양정보 조회 서비스' 활용신청이 승인됐는지 확인하세요.");
                        })
                        .body(String.class);
            } catch (RestClientException e) {
                throw new UpstreamException(PROVIDER, e.getMessage(), e);
            }
            if (body == null || body.isBlank()) throw new UpstreamException(PROVIDER, "빈 응답");
            return body;
        }));
    }

    // ---- 파싱 (static — fixture 단위 테스트) ----------------------------------------

    static Page<Notice> parseNotices(ObjectMapper mapper, String body) {
        return page(mapper, body, (m, n) -> new Notice(text(n, "HOUSE_MANAGE_NO"), text(n, "PBLANC_NO"), text(n, "HOUSE_NM"),
                text(n, "HOUSE_SECD"), text(n, "HOUSE_DTL_SECD"), text(n, "RENT_SECD"), text(n, "SUBSCRPT_AREA_CODE"),
                text(n, "SUBSCRPT_AREA_CODE_NM"), text(n, "HSSPLY_ZIP"), text(n, "HSSPLY_ADRES"), integer(n, "TOT_SUPLY_HSHLDCO"),
                date(n, "RCRIT_PBLANC_DE"), date(n, "RCEPT_BGNDE"), date(n, "RCEPT_ENDDE"), date(n, "SPSPLY_RCEPT_BGNDE"),
                date(n, "SPSPLY_RCEPT_ENDDE"), date(n, "PRZWNER_PRESNATN_DE"), date(n, "CNTRCT_CNCLS_BGNDE"),
                date(n, "CNTRCT_CNCLS_ENDDE"), text(n, "CNSTRCT_ENTRPS_NM"), text(n, "BSNS_MBY_NM"), ym(n, "MVN_PREARNGE_YM"),
                new Flags(yn(n, "SPECLT_RDN_EARTH_AT"), yn(n, "MDAT_TRGET_AREA_SECD"), yn(n, "PARCPRC_ULS_AT"),
                        yn(n, "IMPRMN_BSNS_AT"), yn(n, "PUBLIC_HOUSE_EARTH_AT"), yn(n, "LRSCL_BLDLND_AT"),
                        yn(n, "NPLN_PRVOPR_PUBLIC_HOUSE_AT"), yn(n, "PUBLIC_HOUSE_SPCLW_APPLC_AT")),
                text(n, "PBLANC_URL"), m.writeValueAsString(n)), "HOUSE_MANAGE_NO", "PBLANC_NO", "HOUSE_NM", "HOUSE_SECD", "RCRIT_PBLANC_DE");
    }

    static Page<Remainder> parseRemainders(ObjectMapper mapper, String body) {
        return page(mapper, body, (m, n) -> new Remainder(text(n, "HOUSE_MANAGE_NO"), text(n, "PBLANC_NO"), text(n, "HOUSE_SECD"),
                text(n, "HOUSE_NM"), text(n, "SUBSCRPT_AREA_CODE_NM"), text(n, "HSSPLY_ZIP"), text(n, "HSSPLY_ADRES"),
                integer(n, "TOT_SUPLY_HSHLDCO"), date(n, "RCRIT_PBLANC_DE"), date(n, "SUBSCRPT_RCEPT_BGNDE"),
                date(n, "SUBSCRPT_RCEPT_ENDDE"), ym(n, "MVN_PREARNGE_YM"), text(n, "BSNS_MBY_NM"), text(n, "PBLANC_URL"),
                m.writeValueAsString(n)), "HOUSE_MANAGE_NO", "PBLANC_NO", "HOUSE_NM", "HOUSE_SECD", "RCRIT_PBLANC_DE");
    }

    /** 특별공급 세부(다자녀 · 신혼부부 · 생애최초 · 노부모부양 · 기관추천 · 기타 · 이전기관 · 청년 · 신생아) — 원 필드 이름 그대로 */
    private static final List<String> SPECIAL = List.of("MNYCH_HSHLDCO", "NWWDS_HSHLDCO", "LFE_FRST_HSHLDCO", "OLD_PARNTS_SUPORT_HSHLDCO",
            "INSTT_RECOMEND_HSHLDCO", "ETC_HSHLDCO", "TRANSR_INSTT_ENFSN_HSHLDCO", "YGMN_HSHLDCO", "NWBB_HSHLDCO");
    private static final BigDecimal MANWON = BigDecimal.valueOf(10_000);

    static Page<Model> parseModels(ObjectMapper mapper, String body) {
        return page(mapper, body, (m, n) -> {
            Map<String, Integer> special = new LinkedHashMap<>();
            for (String f : SPECIAL) {
                Integer v = integer(n, f);
                if (v != null) special.put(f, v);
            }
            BigDecimal top = Json.amount(n, "LTTOT_TOP_AMOUNT");
            return new Model(text(n, "HOUSE_MANAGE_NO"), text(n, "PBLANC_NO"), text(n, "MODEL_NO"), text(n, "HOUSE_TY"),
                    Json.amount(n, "SUPLY_AR"), integer(n, "SUPLY_HSHLDCO"), integer(n, "SPSPLY_HSHLDCO"), special,
                    top == null ? null : top.multiply(MANWON), m.writeValueAsString(n));
        }, "HOUSE_MANAGE_NO", "PBLANC_NO", "MODEL_NO");
    }

    private interface Row<T> { T of(ObjectMapper m, JsonNode n); }

    private static <T> Page<T> page(ObjectMapper mapper, String body, Row<T> row, String... required) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (RuntimeException e) {
            throw new UpstreamException(PROVIDER, "JSON 이 아닌 응답: " + body.substring(0, Math.min(200, body.length())));
        }
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            // 200 이면서 오류 봉투 {"code": 음수, "msg": …} — 키 문제는 재시도 없이, 그 밖은 일시 오류로
            String code = Json.text(root, "code"), msg = Json.text(root, "msg");
            if (msg != null && (msg.contains("트래픽") || msg.contains("초과"))) throw new QuotaExceededException(PROVIDER + " " + msg, true);
            if (msg != null && msg.contains("인증키")) throw new ApiKeyRejectedException(PROVIDER, code + " " + msg);
            throw new UpstreamException(PROVIDER, "data 가 없는 응답: " + code + " " + msg);
        }
        List<T> out = new ArrayList<>();
        for (JsonNode n : data.values()) {
            for (String f : required) {
                if (Json.text(n, f) == null) throw new UpstreamException(PROVIDER, "필수 항목 " + f + " 가 빈 행: " + n);
            }
            out.add(row.of(mapper, n));
        }
        return new Page<>(root.path("matchCount").asInt(out.size()), out);
    }

    static String text(JsonNode n, String f) { return Json.text(n, f); }

    static Integer integer(JsonNode n, String f) {
        BigDecimal v = Json.amount(n, f);
        return v == null ? null : v.intValueExact();
    }

    /** 'YYYY-MM-DD' 또는 'YYYYMMDD', 그 밖은 null */
    static LocalDate date(JsonNode n, String f) {
        String s = Json.text(n, f);
        if (s == null) return null;
        try {
            return s.length() == 8 ? LocalDate.parse(s, BASIC) : LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 입주예정월 'YYYYMM' — 월이 01~12 가 아니면 null */
    static String ym(JsonNode n, String f) {
        String s = Json.text(n, f);
        return s != null && s.matches("20\\d\\d(0[1-9]|1[0-2])") ? s : null;
    }

    static Boolean yn(JsonNode n, String f) {
        String s = Json.text(n, f);
        return "Y".equals(s) ? Boolean.TRUE : "N".equals(s) ? Boolean.FALSE : null;
    }
}
