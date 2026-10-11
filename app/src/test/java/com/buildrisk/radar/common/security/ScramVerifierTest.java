package com.buildrisk.radar.common.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** SCRAM-SHA-256 검증값 — 실제 PostgreSQL 로그인은 LeastPrivilegeIT 가 확인 (VERIFICATION 178) */
class ScramVerifierTest {

    @Test
    void 형식은_PostgreSQL_과_같고_salt_가_같으면_같은_값() {
        byte[] salt = Base64.getDecoder().decode("c2FsdHNhbHRzYWx0c2FsdA==");
        String v = ScramVerifier.of("pencil", salt, 4096);
        assertThat(v).matches("SCRAM-SHA-256\\$4096:c2FsdHNhbHRzYWx0c2FsdA==\\$[A-Za-z0-9+/]{43}=:[A-Za-z0-9+/]{43}=");
        assertThat(ScramVerifier.of("pencil", salt, 4096)).isEqualTo(v);
        assertThat(ScramVerifier.of("pencil2", salt, 4096)).isNotEqualTo(v);
    }

    @Test
    void 비밀번호마다_salt_가_달라지고_평문이_들어가지_않는다() {
        String a = ScramVerifier.of("test-app-role-password").orElseThrow(), b = ScramVerifier.of("test-app-role-password").orElseThrow();
        assertThat(a).isNotEqualTo(b).startsWith("SCRAM-SHA-256$4096:").doesNotContain("test-app-role-password");
    }

    @Test
    void 빈_값_ASCII_밖_문자는_만들지_않고_이미_검증값이면_그대로() {
        assertThat(ScramVerifier.of("")).isEmpty();
        assertThat(ScramVerifier.of(null)).isEmpty();
        assertThat(ScramVerifier.of("비밀번호")).isEmpty();
        assertThat(ScramVerifier.of("SCRAM-SHA-256$4096:abc$def:ghi")).contains("SCRAM-SHA-256$4096:abc$def:ghi");
    }
}
