package com.buildrisk.radar.batch;

import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.domain.notify.NotificationService;
import com.buildrisk.radar.domain.notify.WebhookSender;
import com.buildrisk.radar.support.IntegrationTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-025 알림: 규칙 평가와 같은 트랜잭션의 outbox → 구독과 맞춰 알림함 · 웹훅(서명) → 실패 재시도 · 포기,
 * 다음 분기로 이어진 경보는 알리지 않고 다시 열린 경보는 알림, 남의 것 · 관리자 기능 권한
 */
@AutoConfigureMockMvc
class NotificationIT extends IntegrationTest {
    static final String CORP = "00000701", OTHER = "00000702";
    static final RequestPostProcessor ANALYST = user("analyst").roles("ANALYST"), ADMIN_USER = user("admin").roles("ADMIN");

    @Autowired
    BatchLauncher launcher;
    @Autowired
    NotificationService notifications;
    @Autowired
    com.buildrisk.radar.domain.notify.NotificationRepository repo;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    io.micrometer.core.instrument.MeterRegistry registry;
    UUID run;

    @BeforeEach
    void setUp() {
        company(CORP, "알림건설");
        company(OTHER, "다른건설");
        run = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.calc_run (calc_run_id, kind) VALUES (?, 'METRIC')", run);
        // R-C02(이자보상배율 < 1 이 2개 분기 연속, HIGH): 2026Q1 0.7 · 2026Q2 0.5 → 2026Q2 에 참
        for (String corp : List.of(CORP, OTHER)) {
            for (String pk : List.of("2025Q4", "2026Q1", "2026Q2")) metric(corp, pk, "DEBT_RATIO", "150");
            metric(corp, "2025Q4", "INTEREST_COVERAGE", "2.0");
            metric(corp, "2026Q1", "INTEREST_COVERAGE", "0.7");
            metric(corp, "2026Q2", "INTEREST_COVERAGE", "0.5");
        }
    }

    void metric(String corp, String pk, String code, String value) {
        jdbc.update("""
                INSERT INTO risk.company_metric (corp_code, period_key, metric_code, value, status, components, calc_run_id)
                VALUES (?, ?, ?, ?, 'OK', '{"rceptNo":"20260814000701","reprtCode":"11012","numerator":{"amount":1},"denominator":{"amount":2}}', ?)
                ON CONFLICT (corp_code, period_key, metric_code) DO UPDATE SET value = EXCLUDED.value""",
                corp, pk, code, new BigDecimal(value), run);
    }

    /** WireMock 의 post 와 이름이 겹쳐 MockMvc 요청은 이것으로 */
    static MockHttpServletRequestBuilder mvcPost(String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path);
    }

    void evaluate() { assertThat(launcher.runAndWait("ruleEvalJob", Map.of()).getStatus()).isEqualTo(BatchStatus.COMPLETED); }

    int outbox() { return count("SELECT count(*) FROM notify.outbox o JOIN risk.alert a USING (alert_id) WHERE a.rule_code = 'R-C02'"); }

    JsonNode call(MockHttpServletRequestBuilder b, RequestPostProcessor who, Object body, int expect) throws Exception {
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        String res = mvc.perform(b.with(who)).andExpect(status().is(expect)).andReturn().getResponse().getContentAsString();
        return res.isEmpty() ? null : mapper.readTree(res);
    }

    @Test
    void 새_경보는_구독한_사람의_알림함과_서명된_웹훅으로_한_번만() throws Exception {
        call(mvcPost("/api/v1/me/subscriptions"), ANALYST,
                Map.of("name", "재무 경보", "minSeverity", "MEDIUM", "channel", "IN_APP"), 201);
        WM.stubFor(post(urlPathEqualTo("/hook/secret-token")).willReturn(aResponse().withStatus(200)));
        JsonNode created = call(mvcPost("/api/v1/admin/webhooks"), ADMIN_USER,
                Map.of("name", "수신 서버", "kind", "GENERIC", "url", WM.baseUrl() + "/hook/secret-token"), 201);
        String secret = created.get("secret").asString();
        long webhookId = created.get("webhook").get("webhookId").asLong();
        assertThat(created.toString()).doesNotContain("secret-token");                                   // 주소의 토큰 경로는 응답에 없음
        assertThat(jdbc.queryForObject("SELECT url_enc FROM notify.webhook", String.class)).doesNotContain("secret-token");   // DB 에도 평문 없음
        call(mvcPost("/api/v1/me/subscriptions"), ADMIN_USER,
                Map.of("name", "슬랙 대신", "minSeverity", "HIGH", "channel", "WEBHOOK", "webhookId", webhookId, "targetType", "COMPANY"), 201);

        evaluate();
        assertThat(outbox()).isEqualTo(2);                                                                // 두 회사 각 1건 (NEW)
        var cycle = notifications.runOnce();
        assertThat(cycle.events()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM notify.inbox WHERE owner = 'analyst'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM notify.delivery WHERE status = 'SENT'")).isEqualTo(2);
        // 두 경보를 한 메시지로 묶어 한 번 — 서명은 받은 쪽에서 비밀로 다시 계산해 맞아야 함
        WM.verify(1, postRequestedFor(urlPathEqualTo("/hook/secret-token")));
        LoggedRequest req = WM.findAll(postRequestedFor(urlPathEqualTo("/hook/secret-token"))).getFirst();
        String ts = req.getHeader("X-BuildRisk-Timestamp");
        assertThat(req.getHeader("X-BuildRisk-Signature")).isEqualTo("sha256=" + WebhookSender.sign(secret, ts + "." + req.getBodyAsString()));
        assertThat(mapper.readTree(req.getBodyAsString()).get("alerts")).hasSize(2);

        // 다시 평가해도 새 사건 없음 · 다시 돌려도 다시 보내지 않음
        evaluate();
        assertThat(outbox()).isEqualTo(2);
        notifications.runOnce();
        WM.verify(1, postRequestedFor(urlPathEqualTo("/hook/secret-token")));

        mvc.perform(get("/api/v1/me/notifications").with(ANALYST)).andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(2)).andExpect(jsonPath("$.items[0].ruleCode").value("R-C02"));
        mvc.perform(get("/api/v1/me/notifications").with(ADMIN_USER)).andExpect(jsonPath("$.total").value(0));   // 관리자는 웹훅 구독만
        call(mvcPost("/api/v1/me/notifications/read"), ANALYST, Map.of("all", true), 200);
        mvc.perform(get("/api/v1/me/notifications/unread-count").with(ANALYST)).andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    void 권한_본인_것만_웹훅은_관리자만() throws Exception {
        mvc.perform(get("/api/v1/me/notifications")).andExpect(status().isUnauthorized());                     // 조회도 로그인 필요
        mvc.perform(get("/api/v1/admin/webhooks").with(ANALYST)).andExpect(status().isForbidden());
        JsonNode s = call(mvcPost("/api/v1/me/subscriptions"), ANALYST,
                Map.of("name", "내 구독", "minSeverity", "LOW", "channel", "IN_APP"), 201);
        long id = s.get("subscriptionId").asLong();
        call(delete("/api/v1/me/subscriptions/" + id), ADMIN_USER, null, 404);                                  // 남의 구독은 없는 것처럼
        call(mvcPost("/api/v1/me/subscriptions"), ANALYST,
                Map.of("name", "웹훅", "minSeverity", "LOW", "channel", "WEBHOOK", "webhookId", 1), 403);
        call(mvcPost("/api/v1/me/subscriptions"), ANALYST,
                Map.of("name", "이상한 규칙", "minSeverity", "LOW", "channel", "IN_APP", "ruleCodes", List.of("R-ZZ9")), 400);
        call(mvcPost("/api/v1/admin/webhooks"), ADMIN_USER,
                Map.of("name", "ftp", "kind", "SLACK", "url", "ftp://example.com/x"), 400);
        call(delete("/api/v1/me/subscriptions/" + id), ANALYST, null, 204);
    }

    @Test
    void 관심_목록만_구독하면_목록에_있는_기업의_경보만() throws Exception {
        call(mvcPost("/api/v1/me/subscriptions"), ANALYST,
                Map.of("name", "관심 기업", "minSeverity", "LOW", "channel", "IN_APP", "watchOnly", true), 201);
        call(put("/api/v1/me/watch/COMPANY/" + CORP), ANALYST, null, 204);
        call(put("/api/v1/me/watch/COMPANY/99999999"), ANALYST, null, 404);
        evaluate();
        notifications.runOnce();
        assertThat(jdbc.queryForList("SELECT a.target_key FROM notify.inbox n JOIN risk.alert a USING (alert_id)", String.class))
                .containsExactly(CORP);
    }

    @Test
    void 다음_분기로_이어진_경보는_알리지_않고_풀렸다가_다시_참이면_다시_열림으로_알림() throws Exception {
        call(mvcPost("/api/v1/me/subscriptions"), ANALYST,
                Map.of("name", "전체", "minSeverity", "LOW", "channel", "IN_APP", "targetType", "COMPANY"), 201);
        jdbc.update("DELETE FROM risk.company_metric WHERE corp_code = ?", OTHER);
        evaluate();
        assertThat(outbox()).isEqualTo(1);
        metric(CORP, "2026Q3", "DEBT_RATIO", "150");
        metric(CORP, "2026Q3", "INTEREST_COVERAGE", "0.4");                  // 2026Q3 로 이어짐 → 2026Q2 경보는 SUPERSEDED, 새 경보는 알리지 않음
        evaluate();
        assertThat(outbox()).isEqualTo(1);
        metric(CORP, "2026Q3", "INTEREST_COVERAGE", "1.5");                  // 풀림 → RESOLVED
        evaluate();
        metric(CORP, "2026Q3", "INTEREST_COVERAGE", "0.4");                  // 다시 참 → 같은 경보가 다시 열림
        evaluate();
        assertThat(jdbc.queryForList("SELECT kind FROM notify.outbox ORDER BY event_id", String.class)).containsExactly("NEW", "REOPENED");
        notifications.runOnce();
        assertThat(jdbc.queryForList("SELECT kind FROM notify.inbox WHERE owner = 'analyst' ORDER BY inbox_id", String.class))
                .containsExactly("NEW", "REOPENED");
    }

    @Test
    void 웹훅_실패는_백오프로_다시_보내고_다시_보내도_같은_실패는_포기() throws Exception {
        WM.stubFor(post(urlPathEqualTo("/flaky")).willReturn(aResponse().withStatus(503)));
        JsonNode created = call(mvcPost("/api/v1/admin/webhooks"), ADMIN_USER,
                Map.of("name", "불안정", "kind", "SLACK", "url", WM.baseUrl() + "/flaky"), 201);
        assertThat(created.path("secret").isMissingNode() || created.path("secret").isNull()).isTrue();      // SLACK 은 서명 비밀 없음
        call(mvcPost("/api/v1/me/subscriptions"), ADMIN_USER,
                Map.of("name", "웹훅", "minSeverity", "LOW", "channel", "WEBHOOK", "webhookId", created.get("webhook").get("webhookId").asLong()), 201);
        evaluate();
        notifications.runOnce();
        var row = jdbc.queryForMap("SELECT status, attempts, last_error, next_at > now() AS later FROM notify.delivery LIMIT 1");
        assertThat(row).containsEntry("status", "PENDING").containsEntry("attempts", 1).containsEntry("later", true);
        assertThat((String) row.get("last_error")).isEqualTo("HTTP 503");

        // 받는 쪽이 웹훅을 지움(404) — 다시 보내도 같으므로 바로 포기
        WM.stubFor(post(urlPathEqualTo("/flaky")).willReturn(aResponse().withStatus(404)));
        jdbc.update("UPDATE notify.delivery SET next_at = now() - interval '1 second'");
        notifications.runOnce();
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM notify.delivery", String.class)).containsExactly("DEAD");
        assertThat(registry.get("buildrisk.notify.deliveries").tag("result", "dead").counter().count()).isGreaterThanOrEqualTo(2);
        assertThat(registry.get("buildrisk.notify.deliveries").tag("result", "retry").counter().count()).isGreaterThanOrEqualTo(2);
        mvc.perform(get("/api/v1/admin/webhooks").with(ADMIN_USER)).andExpect(jsonPath("$.items[0].dead").value(2))
                .andExpect(jsonPath("$.items[0].lastStatus").value("HTTP 404"));
    }

    @Test
    void 다시_보내도_같은_전송은_같은_Delivery_ID() throws Exception {
        // 재시도마다 새 UUID 라 받는 쪽이 중복을 걸러낼 수 없었음 — 응답 시간 초과는 상대가 이미 처리했을 수 있는데도 (VERIFICATION 131)
        WM.stubFor(post(urlPathEqualTo("/dedupe")).inScenario("d").whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("ok"));
        WM.stubFor(post(urlPathEqualTo("/dedupe")).inScenario("d").whenScenarioStateIs("ok").willReturn(aResponse().withStatus(200)));
        JsonNode created = call(mvcPost("/api/v1/admin/webhooks"), ADMIN_USER,
                Map.of("name", "중복 제거", "kind", "GENERIC", "url", WM.baseUrl() + "/dedupe"), 201);
        call(mvcPost("/api/v1/me/subscriptions"), ADMIN_USER,
                Map.of("name", "웹훅", "minSeverity", "LOW", "channel", "WEBHOOK", "webhookId", created.get("webhook").get("webhookId").asLong()), 201);
        evaluate();
        notifications.runOnce();                                              // 503
        jdbc.update("UPDATE notify.delivery SET next_at = now() - interval '1 second'");
        notifications.runOnce();                                              // 200
        List<String> ids = WM.findAll(postRequestedFor(urlPathEqualTo("/dedupe"))).stream()
                .map(r -> r.getHeader("X-BuildRisk-Delivery")).toList();
        assertThat(ids).hasSize(2);
        assertThat(ids.get(1)).isEqualTo(ids.get(0));
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM notify.delivery", String.class)).containsExactly("SENT");

        // 임대(2분)가 끝나 다른 worker 가 다시 가져간 행은, 늦게 끝난 첫 worker 가 결과를 덮어쓰지 않음
        jdbc.update("UPDATE notify.delivery SET status = 'PENDING', next_at = now() - interval '1 second', sent_at = NULL");
        var first = repo.claimDue(10);
        jdbc.update("UPDATE notify.delivery SET lease_until = now() - interval '1 second'");
        var second = repo.claimDue(10);
        assertThat(second).hasSameSizeAs(first).isNotEmpty();
        List<Long> claimedIds = first.stream().map(c -> c.deliveryId()).toList();
        assertThat(repo.markSent(claimedIds, first.getFirst().leaseUntil())).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM notify.delivery", String.class)).containsExactly("SENDING");
        assertThat(repo.markSent(claimedIds, second.getFirst().leaseUntil())).isEqualTo(claimedIds.size());
    }
}

