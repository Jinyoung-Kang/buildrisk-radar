package com.buildrisk.radar.domain.notify;

import com.buildrisk.radar.common.AppProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 웹훅 전송 (ADR-025) — JDK HttpClient(의존성 추가 없음), 리다이렉트 안 따라감 · 연결 3초 · 응답 5초 · 응답 본문은 읽지 않음.
 * SLACK: Incoming Webhook 형식 {"text": …}. GENERIC: JSON + X-BuildRisk-Signature = sha256=HMAC(비밀, 타임스탬프 + "." + 본문)
 * — 받는 쪽이 위조 · 재전송(타임스탬프)을 걸러낼 수 있게. X-BuildRisk-Delivery 는 받는 쪽 중복 제거용.
 */
@Component
public class WebhookSender {
    public static final int MAX_ALERTS_PER_MESSAGE = 20;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper mapper;
    private final boolean allowInsecure;
    private final String baseUrl;

    public WebhookSender(ObjectMapper mapper, AppProperties props) {
        this.mapper = mapper;
        this.allowInsecure = props.notification() != null && props.notification().allowInsecureTargets();
        this.baseUrl = props.notification() == null ? "http://localhost:3400" : props.notification().publicBaseUrl().replaceAll("/+$", "");
    }

    public enum Kind { SLACK, GENERIC }

    public record Target(String url, Kind kind, String secret) {}

    public record AlertItem(long alertId, String kind, String ruleCode, String severity, String title, String targetType,
                            String targetKey, String targetName, String asOf) {}

    /** retryable: 429 · 5xx · 시간 초과 · 연결 실패. 그 밖의 4xx · 주소 거부는 다시 보내도 같으므로 바로 포기 */
    public record Result(boolean ok, int status, String error, boolean retryable) {}

    public Result send(Target t, List<AlertItem> alerts, boolean test) {
        URI uri;
        try {
            uri = WebhookGuard.validate(t.url(), allowInsecure);
            WebhookGuard.checkResolved(uri, allowInsecure);
        } catch (WebhookGuard.Rejected e) {
            return new Result(false, 0, "주소 거부: " + e.getMessage(), false);
        }
        String body = t.kind() == Kind.SLACK ? slack(alerts, test) : generic(alerts, test);
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "BuildRisk-Radar-Webhook/1")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (t.kind() == Kind.GENERIC) {
            String ts = String.valueOf(Instant.now().getEpochSecond());
            req.header("X-BuildRisk-Timestamp", ts)
                    .header("X-BuildRisk-Delivery", UUID.randomUUID().toString())
                    .header("X-BuildRisk-Signature", "sha256=" + sign(t.secret(), ts + "." + body));
        }
        try {
            HttpResponse<Void> res = http.send(req.build(), HttpResponse.BodyHandlers.discarding());
            int s = res.statusCode();
            if (s >= 200 && s < 300) return new Result(true, s, null, false);
            boolean retry = s == 408 || s == 429 || s >= 500;
            return new Result(false, s, "HTTP " + s + (s >= 300 && s < 400 ? " (리다이렉트는 따라가지 않음)" : ""), retry);
        } catch (HttpTimeoutException e) {
            return new Result(false, 0, "시간 초과", true);
        } catch (IOException e) {
            return new Result(false, 0, "연결 실패: " + e.getClass().getSimpleName(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, 0, "중단됨", true);
        }
    }

    public static String sign(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    String link(long alertId) { return baseUrl + "/alerts?id=" + alertId; }

    String slack(List<AlertItem> alerts, boolean test) {
        StringBuilder sb = new StringBuilder(test ? "*[시험]* 건설·부동산 위험 모니터 웹훅 연결 확인\n" : "*건설·부동산 위험 모니터 — 새 경보 " + alerts.size() + "건*\n");
        for (AlertItem a : alerts) {
            sb.append("• [").append(a.severity()).append("] ").append(esc(a.ruleCode())).append(' ')
                    .append(esc(a.targetName() == null ? a.targetKey() : a.targetName())).append(" — ").append(esc(a.title()))
                    .append(" (").append(esc(a.asOf())).append(a.kind().equals("REOPENED") ? ", 다시 열림" : "").append(") <")
                    .append(link(a.alertId())).append("|보기>\n");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", sb.toString().trim());
        return mapper.writeValueAsString(m);
    }

    String generic(List<AlertItem> alerts, boolean test) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AlertItem a : alerts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("alertId", a.alertId());
            m.put("kind", a.kind());
            m.put("ruleCode", a.ruleCode());
            m.put("severity", a.severity());
            m.put("title", a.title());
            m.put("targetType", a.targetType());
            m.put("targetKey", a.targetKey());
            m.put("targetName", a.targetName());
            m.put("asOf", a.asOf());
            m.put("url", link(a.alertId()));
            list.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", test ? "test" : "alerts");
        m.put("sentAt", Instant.now().toString());
        m.put("alerts", list);
        return mapper.writeValueAsString(m);
    }

    /** Slack mrkdwn 은 & < > 만 이스케이프 (링크 · 멘션 주입 방지) */
    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
