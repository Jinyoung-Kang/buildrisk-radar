package com.buildrisk.radar.common.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AuditFilterTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    private String redactedUrl(String url) {
        var body = mapper.createObjectNode().put("url", url);
        return AuditFilter.redact(body).get("url").asString();
    }

    @Test
    void 주소는_출처만_남기고_사용자_정보는_뺀다() {
        // 출처 정규식이 '://' 뒤 '/' 전까지를 통째로 남겨 user:pass@ 가 감사 로그에 평문으로 남았음 (VERIFICATION 158)
        assertThat(redactedUrl("https://hook-user:hook-pass-7@hooks.example.com:8443/services/T0")).isEqualTo("https://hooks.example.com:8443/***");
        assertThat(redactedUrl("https://a@b@hooks.example.com/x")).isEqualTo("https://hooks.example.com/***");
        // 역슬래시는 브라우저 · 일부 클라이언트가 '/' 로 읽으므로 경로의 시작으로 봄
        assertThat(redactedUrl("https://hooks.example.com\\services\\T0-secret")).isEqualTo("https://hooks.example.com/***");
        // 기존 동작 고정
        assertThat(redactedUrl("http://127.0.0.1:9/hook/T0?key=q")).isEqualTo("http://127.0.0.1:9/***");
        assertThat(redactedUrl("http://[::1]:9/hook")).isEqualTo("http://[::1]:9/***");
        assertThat(redactedUrl("not a url")).isEqualTo("/***");
    }
}
