package com.buildrisk.radar.adapters.dart;

import com.buildrisk.radar.adapters.common.UpstreamException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** corpCode Zip 해제 상한 — 압축 폭탄이면 tmpfs(/tmp, worker 메모리)를 채우기 전에 멈춤 (VERIFICATION 116) */
class BoundedCopyTest {
    @Test
    void 상한을_넘으면_멈추고_반쯤_쓴_파일을_지운다(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("CORPCODE.xml");
        assertThatThrownBy(() -> DartClient.copyBounded(new ByteArrayInputStream(new byte[2048]), out, 1024))
                .isInstanceOf(UpstreamException.class).hasMessageContaining("상한");
        assertThat(Files.exists(out)).isFalse();
    }

    @Test
    void 상한_안이면_그대로_쓴다(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("CORPCODE.xml");
        DartClient.copyBounded(new ByteArrayInputStream(new byte[1024]), out, 1024);
        assertThat(Files.size(out)).isEqualTo(1024);
    }
}
