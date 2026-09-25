package com.buildrisk.radar.adapters.datagokr;

import com.buildrisk.radar.adapters.common.ApiKeyMissingException;
import com.buildrisk.radar.adapters.common.ApiKeyRejectedException;
import com.buildrisk.radar.adapters.common.ApiQuotaService;
import com.buildrisk.radar.adapters.common.ExternalApiMetrics;
import com.buildrisk.radar.adapters.common.HttpSupport;
import com.buildrisk.radar.adapters.common.QuotaExceededException;
import com.buildrisk.radar.adapters.common.Throttle;
import com.buildrisk.radar.adapters.common.UpstreamException;
import com.buildrisk.radar.common.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공공데이터포털 공통 호출 — 인증키(serviceKey) · API 별 일일 상한 · 게이트웨이 오류 해석.
 * <ul>
 *   <li>일일 상한: ops.api_quota(provider) 가 설정값에 닿으면 호출 전에 {@link QuotaExceededException} → Job STOPPED(다음 날 이어서)</li>
 *   <li>게이트웨이 오류 XML(OpenAPI_ServiceResponse) 22 · HTTP 429 = 트래픽 초과, 30~32 · 401/403 = 키 문제(재시도 없이 실패)</li>
 *   <li>키는 URI 변수로 넣어 '+' '/' '=' 가 있는 인코딩 전 키도 한 번만 인코딩되게 합니다.</li>
 * </ul>
 */
@Component
public class DataGoKrGateway {
    private static final Pattern REASON = Pattern.compile("<returnReasonCode>\\s*(\\d+)\\s*</returnReasonCode>");
    private static final Pattern AUTH_MSG = Pattern.compile("<returnAuthMsg>\\s*([^<]*)</returnAuthMsg>");
    private final AppProperties.DataGoKr cfg;
    private final RestClient http;
    private final ApiQuotaService quota;
    private final ExternalApiMetrics metrics;
    private final Throttle throttle;

    public DataGoKrGateway(AppProperties props, ApiQuotaService quota, ExternalApiMetrics metrics) {
        this.cfg = props.dataGoKr();
        this.http = HttpSupport.client(cfg.baseUrl(), Duration.ofSeconds(60));
        this.quota = quota;
        this.metrics = metrics;
        this.throttle = new Throttle(cfg.minIntervalMs());
    }

    public boolean configured() { return !HttpSupport.blank(cfg.serviceKey()); }

    public int dailyLimit() { return cfg.dailyCallLimit(); }

    public int usedToday(String provider) { return quota.used(provider); }

    public String get(String provider, String operation, String path, Map<String, Object> params) {
        if (!configured()) throw new ApiKeyMissingException("DATA_GO_KR_KEY");
        int used = quota.used(provider);
        if (used >= cfg.dailyCallLimit()) {
            throw new QuotaExceededException(provider + " 오늘 호출 " + used + "건 — 설정한 일일 상한("
                    + cfg.dailyCallLimit() + ")에 도달해 멈춥니다. 내일 다시 실행하면 이어서 수집합니다.", false);
        }
        return HttpSupport.retry(3, () -> metrics.time(provider, operation, () -> {
            throttle.acquire();
            quota.increment(provider);
            String body;
            try {
                body = http.get().uri(u -> {
                            u.path(path);
                            params.forEach(u::queryParam);
                            return u.queryParam("serviceKey", "{serviceKey}").build(cfg.serviceKey());
                        })
                        .retrieve()
                        .onStatus(s -> s.value() == 429, (req, res) -> {
                            throw new QuotaExceededException(provider + " 트래픽 초과(HTTP 429)", true);
                        })
                        .onStatus(s -> s.value() == 401 || s.value() == 403, (req, res) -> {
                            throw new ApiKeyRejectedException(provider, "인증 실패(HTTP " + res.getStatusCode().value()
                                    + ") — 공공데이터포털에서 이 API 활용신청이 승인됐는지 확인하세요.");
                        })
                        .body(String.class);
            } catch (RestClientException e) {
                throw new UpstreamException(provider, e.getMessage(), e);
            }
            if (body == null || body.isBlank()) throw new UpstreamException(provider, "빈 응답");
            checkGatewayError(provider, body);
            return body;
        }));
    }

    /** 게이트웨이가 서비스 응답 대신 돌려주는 오류 XML */
    static void checkGatewayError(String provider, String body) {
        if (!body.contains("OpenAPI_ServiceResponse")) return;
        Matcher m = REASON.matcher(body);
        String code = m.find() ? m.group(1) : "?";
        Matcher a = AUTH_MSG.matcher(body);
        String msg = a.find() ? a.group(1).trim() : "SERVICE ERROR";
        switch (code) {
            case "22" -> throw new QuotaExceededException(provider + " 일일 트래픽 초과(" + msg + ")", true);
            case "20", "30", "31", "32", "33" -> throw new ApiKeyRejectedException(provider, code + " " + msg);
            default -> throw new UpstreamException(provider, "게이트웨이 오류 " + code + " " + msg);
        }
    }
}
