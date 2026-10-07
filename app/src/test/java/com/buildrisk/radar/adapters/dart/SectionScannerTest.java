package com.buildrisk.radar.adapters.dart;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 정기보고서 구간 추출 — 표지가 청크 경계에 걸려도, 끝 표지가 없으면 상한에서 자름 */
class SectionScannerTest {
    static final byte[] START = "AASSOCNOTE=\"D-0-11-2-0\"".getBytes(StandardCharsets.US_ASCII);
    static final byte[] END = "AASSOCNOTE=\"D-0-11-3-0\"".getBytes(StandardCharsets.US_ASCII);
    static final String DOC = "<DOCUMENT>" + "x".repeat(20_000)
            + "<TITLE ATOC=\"Y\" AASSOCNOTE=\"D-0-11-2-0\">2. 우발부채 등에 관한 사항</TITLE><P>PF 보증</P><TABLE>…</TABLE>"
            + "<TITLE ATOC=\"Y\" AASSOCNOTE=\"D-0-11-3-0\">3. 제재 등</TITLE>" + "y".repeat(5_000) + "</DOCUMENT>";

    static SectionScanner feed(byte[] bytes, int chunk, int keep) {
        SectionScanner sc = new SectionScanner(START, END, keep);
        for (int i = 0; i < bytes.length; i += chunk) {
            int n = Math.min(chunk, bytes.length - i);
            byte[] buf = new byte[n];
            System.arraycopy(bytes, i, buf, 0, n);
            if (sc.feed(buf, n)) break;
        }
        return sc;
    }

    @Test
    void 시작_표지의_TITLE_부터_끝_표지의_TITLE_직전까지_청크_크기와_무관하게() {
        byte[] bytes = DOC.getBytes(StandardCharsets.UTF_8);
        for (int chunk : new int[]{7, 64, 1024, 65536}) {                 // 표지가 청크 경계에 걸리는 경우 포함
            String s = new String(feed(bytes, chunk, 1 << 20).bytes(), StandardCharsets.UTF_8);
            assertThat(s).startsWith("<TITLE ATOC=\"Y\" AASSOCNOTE=\"D-0-11-2-0\">").contains("PF 보증")
                    .doesNotContain("3. 제재 등").doesNotContain("yyy");
        }
    }

    @Test
    void 시작_표지가_없으면_찾지_못함_끝_표지가_없으면_상한에서_자름() {
        assertThat(feed("<DOCUMENT>없음</DOCUMENT>".getBytes(StandardCharsets.UTF_8), 64, 1 << 20).found()).isFalse();
        String noEnd = "<TITLE AASSOCNOTE=\"D-0-11-2-0\">2.</TITLE>" + "z".repeat(10_000);
        SectionScanner sc = feed(noEnd.getBytes(StandardCharsets.UTF_8), 512, 2_000);
        assertThat(sc.truncated()).isTrue();
        assertThat(sc.bytes()).hasSize(2_000);
    }
}
