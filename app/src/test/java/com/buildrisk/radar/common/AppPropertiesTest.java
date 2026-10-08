package com.buildrisk.radar.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppPropertiesTest {
    private static AppProperties withToken(String token) {
        return new AppProperties("api", "seed", token, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void 서비스_토큰은_비우거나_32자_이상() {
        // ADMIN 권한 토큰 — 짧으면 분당 1,200 요청 한도 아래에서도 맞춰 볼 수 있음
        assertThatThrownBy(() -> withToken("short-token")).hasMessageContaining("32자 이상");
        assertThatCode(() -> withToken(null)).doesNotThrowAnyException();
        assertThatCode(() -> withToken("")).doesNotThrowAnyException();
        assertThatCode(() -> withToken("x".repeat(32))).doesNotThrowAnyException();
    }
}
