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
    void 사용자_정보에_구분_문자가_있어_출처를_가릴_수_없으면_출처도_남기지_않는다() {
        // 권한부를 첫 '/ ? # \ 공백' 에서 잘라 그 안에서만 '@' 를 찾아, 비밀번호에 그 문자가 있으면 앞부분이 호스트로 남았음 (VERIFICATION 166)
        for (String url : java.util.List.of("https://user:pa/ss@hooks.example.com/x", "https://user:p?ss@hooks.example.com/x",
                "https://user:p#ss@hooks.example.com/x", "https://user:p ss@hooks.example.com/x", "https://user:p\\ss@hooks.example.com/x")) {
            assertThat(redactedUrl(url)).as(url).isEqualTo("/***");
        }
        // 경로 · 쿼리의 '@' 도 사용자 정보와 구분할 수 없어 같은 처리 — 감사에는 출처가 빠질 뿐 값은 남지 않음
        assertThat(redactedUrl("https://hooks.example.com/a@b")).isEqualTo("/***");
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
