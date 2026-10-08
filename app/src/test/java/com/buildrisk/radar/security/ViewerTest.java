package com.buildrisk.radar.security;

import com.buildrisk.radar.common.security.Viewer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ViewerTest {
    @Test
    void 실패_메시지는_첫_줄만_200자까지() {
        String trace = "org.springframework.dao.DataAccessException: 연결 끊김\n\tat com.buildrisk.radar.batch.Foo.bar(Foo.java:12)";
        assertThat(Viewer.firstLine(trace)).isEqualTo("org.springframework.dao.DataAccessException: 연결 끊김");
        assertThat(Viewer.firstLine("x".repeat(300))).hasSize(201).endsWith("…");
        assertThat(Viewer.firstLine("")).isEmpty();
        assertThat(Viewer.firstLine(null)).isNull();
    }

    @Test
    void worker_별칭은_호스트를_숨기고_같은_값은_같은_별칭() {
        String a = Viewer.alias("worker-1@0123456789ab");
        assertThat(a).matches("worker-[0-9a-f]{6}").isEqualTo(Viewer.alias("worker-1@0123456789ab"))
                .isNotEqualTo(Viewer.alias("worker-1@ba9876543210"));
        assertThat(Viewer.alias(null)).isNull();
    }
}
