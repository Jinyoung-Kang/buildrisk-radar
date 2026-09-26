package com.buildrisk.radar.security;

import com.buildrisk.radar.common.security.RateLimitFilter;
import com.buildrisk.radar.common.security.RateLimiter;
import com.buildrisk.radar.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-013 — 세션 로그인 · CSRF · 역할 · 로그인 잠금 · 레이트리밋 · 감사 로그 · 보안 헤더 */
@AutoConfigureMockMvc
class SecurityIT extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    RateLimiter limiter;
    @Autowired
    ObjectMapper mapper;

    /** 브라우저처럼 쿠키를 들고 다니는 클라이언트 */
    class Browser {
        final List<Cookie> jar = new ArrayList<>();
        final String ip;

        Browser(String ip) { this.ip = ip; }

        MvcResult send(MockHttpServletRequestBuilder b) throws Exception {
            b.with(r -> { r.setRemoteAddr(ip); return r; });
            if (!jar.isEmpty()) b.cookie(jar.toArray(Cookie[]::new));
            MvcResult r = mvc.perform(b).andReturn();
            for (Cookie c : r.getResponse().getCookies()) {
                jar.removeIf(o -> o.getName().equals(c.getName()));
                if (c.getMaxAge() != 0 && !c.getValue().isEmpty()) jar.add(c);
            }
            return r;
        }

        String cookie(String name) {
            return jar.stream().filter(c -> c.getName().equals(name)).map(Cookie::getValue).findFirst().orElse(null);
        }

        /** 변경 요청 — SPA 처럼 XSRF-TOKEN 쿠키 값을 헤더로 */
        MvcResult mutate(MockHttpServletRequestBuilder b) throws Exception {
            String xsrf = cookie("XSRF-TOKEN");
            if (xsrf != null) b.header("X-XSRF-TOKEN", xsrf);
            return send(b.contentType(MediaType.APPLICATION_JSON));
        }

        MvcResult login(String user, String password) throws Exception {
            if (cookie("XSRF-TOKEN") == null) send(get("/api/v1/auth/me"));
            return mutate(post("/api/v1/auth/login").content("{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}"));
        }
    }

    private long alert(String corp) {
        company(corp, "보안건설");
        return jdbc.queryForObject("""
                INSERT INTO risk.alert (rule_code, rule_version, target_type, target_key, as_of, severity, title, message, evidence, status)
                VALUES ('R-C02', 1, 'COMPANY', ?, '2026Q2', 'HIGH', 't', 'm', '{"condition":"x"}', 'OPEN') RETURNING alert_id""",
                Long.class, corp);
    }

    @Test
    void 조회는_공개이고_보안_헤더가_붙는다() throws Exception {
        mvc.perform(get("/api/v1/rules")).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")));
        mvc.perform(get("/api/v1/auth/me")).andExpect(jsonPath("$.authenticated").value(false));
        mvc.perform(get("/api/v1/admin/audit")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 세션_로그인은_CSRF_를_요구하고_역할대로_허용한다() throws Exception {
        long id = alert("00000101");
        Browser b = new Browser("10.1.0.1");
        // CSRF 토큰 없이 로그인 → 403 (로그인 CSRF 방지)
        MvcResult noCsrf = b.send(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"analyst\",\"password\":\"" + ANALYST_PASSWORD + "\"}"));
        assertThat(noCsrf.getResponse().getStatus()).isEqualTo(403);
        assertThat(noCsrf.getResponse().getContentAsString()).contains("CSRF_INVALID");

        String before = b.cookie("XSRF-TOKEN");
        MvcResult ok = b.login("analyst", ANALYST_PASSWORD);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"roles\":[\"ANALYST\"]");
        assertThat(b.cookie("BR_SESSION")).isNotNull();
        assertThat(b.cookie("XSRF-TOKEN")).isNotEqualTo(before);                  // 로그인 후 CSRF 토큰 교체
        String setCookie = String.join("\n", ok.getResponse().getHeaders("Set-Cookie"));
        assertThat(setCookie).contains("BR_SESSION=").contains("HttpOnly").containsIgnoringCase("SameSite=Strict");

        // 세션 쿠키가 있는 변경 요청은 CSRF 헤더 필수
        MvcResult forged = b.send(patch("/api/v1/alerts/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACK\"}"));
        assertThat(forged.getResponse().getStatus()).isEqualTo(403);
        MvcResult ack = b.mutate(patch("/api/v1/alerts/" + id).content("{\"status\":\"ACK\"}"));
        assertThat(ack.getResponse().getStatus()).isEqualTo(200);
        assertThat(ack.getResponse().getContentAsString()).contains("\"ackedBy\":\"analyst\"");

        // ANALYST 는 규칙 변경·감사 조회 불가
        MvcResult rule = b.mutate(put("/api/v1/rules/R-C02").content("{\"params\":{\"threshold\":0.8}}"));
        assertThat(rule.getResponse().getStatus()).isEqualTo(403);
        assertThat(rule.getResponse().getContentAsString()).contains("FORBIDDEN");
        assertThat(b.send(get("/api/v1/admin/audit")).getResponse().getStatus()).isEqualTo(403);

        // 로그아웃 → 익명
        assertThat(b.mutate(post("/api/v1/auth/logout")).getResponse().getStatus()).isEqualTo(204);
        assertThat(b.send(get("/api/v1/auth/me")).getResponse().getContentAsString()).contains("\"authenticated\":false");
    }

    @Test
    void 관리자_세션은_규칙을_바꾸고_감사_로그에_남는다() throws Exception {
        jdbc.update("DELETE FROM ops.audit_log");
        Browser b = new Browser("10.1.0.2");
        assertThat(b.login("admin", ADMIN_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        MvcResult r = b.mutate(put("/api/v1/rules/R-C02").content("{\"params\":{\"threshold\":0.85},\"changeNote\":\"감사 테스트\"}"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentAsString()).contains("\"createdBy\":\"admin\"");

        MvcResult audit = b.send(get("/api/v1/admin/audit").param("actor", "admin"));
        assertThat(audit.getResponse().getStatus()).isEqualTo(200);
        var items = mapper.readTree(audit.getResponse().getContentAsString()).get("items");
        assertThat(items.findValuesAsString("action")).contains("PUT /api/v1/rules/{ruleCode}", "LOGIN_SUCCESS");
        var put = items.get(0);
        assertThat(put.get("authType").asString()).isEqualTo("SESSION");
        assertThat(put.get("status").asInt()).isEqualTo(200);
        assertThat(put.get("detail").get("body").get("changeNote").asString()).isEqualTo("감사 테스트");
        assertThat(put.get("traceId").asString()).hasSize(26);
        // 로그인 본문(비밀번호)은 감사 로그에 남지 않음
        assertThat(count("SELECT count(*) FROM ops.audit_log WHERE detail::text LIKE ?", "%" + ADMIN_PASSWORD + "%")).isZero();
    }

    @Test
    void 잘못된_서비스_토큰과_익명_변경_시도도_감사된다() throws Exception {
        jdbc.update("DELETE FROM ops.audit_log");
        mvc.perform(put("/api/v1/rules/R-C02").header("X-Admin-Token", "wrong").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/batch/jobs/ruleEvalJob/launch")).andExpect(status().isUnauthorized());
        assertThat(count("SELECT count(*) FROM ops.audit_log WHERE actor = 'anonymous' AND status = 401")).isEqualTo(2);
        // 거부된 요청도 경로 템플릿으로 기록 (행위별 집계가 가능하게)
        assertThat(jdbc.queryForList("SELECT action FROM ops.audit_log WHERE status = 401", String.class))
                .containsExactlyInAnyOrder("PUT /api/v1/rules/{ruleCode}", "POST /api/v1/batch/jobs/{jobName}/launch");
    }

    @Test
    void 연속_로그인_실패는_잠기고_같은_메시지를_준다() throws Exception {
        Browser b = new Browser("10.1.0.3");
        MvcResult unknown = b.login("nobody", "whatever-password");
        MvcResult wrong = b.login("analyst", "wrong-password-123");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrong.getResponse().getContentAsString()).contains("LOGIN_FAILED")
                .contains(mapper.readTree(unknown.getResponse().getContentAsString()).get("message").asString());  // 계정 존재 여부 비노출
        for (int i = 0; i < 4; i++) b.login("analyst", "wrong-password-123");
        MvcResult locked = b.login("analyst", ANALYST_PASSWORD);                   // 올바른 비밀번호도 잠금 중엔 거부
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(locked.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(new Browser("10.1.0.4").login("analyst", ANALYST_PASSWORD).getResponse().getStatus()).isEqualTo(200);  // 다른 IP 는 영향 없음
    }

    @Test
    void IP_당_분당_요청_한도를_넘으면_429() throws Exception {
        var filter = new RateLimitFilter(limiter, 3, mapper);
        int[] statuses = new int[4];
        for (int i = 0; i < 4; i++) {
            var req = new MockHttpServletRequest("GET", "/api/v1/rules");
            req.setRemoteAddr("10.9.9.9");
            var res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            statuses[i] = res.getStatus();
            if (i == 3) assertThat(res.getHeader("Retry-After")).isNotNull();
        }
        assertThat(statuses).containsExactly(200, 200, 200, 429);
    }
}
