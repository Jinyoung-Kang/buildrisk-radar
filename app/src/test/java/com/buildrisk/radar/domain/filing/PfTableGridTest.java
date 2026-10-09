package com.buildrisk.radar.domain.filing;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 원문 표 격자 펼치기 — 공시 원문(회사가 쓴 HTML)의 병합 값은 믿지 않음 (VERIFICATION 132) */
class PfTableGridTest {
    @Test
    void 터무니없는_colspan_rowspan_도_메모리를_다_쓰지_않는다() {
        // COLSPAN="2147483647" 하나로 격자를 펼치다 OutOfMemoryError(1.5초) — worker 는 -XX:+ExitOnOutOfMemoryError 라 프로세스가 죽음
        var table = Jsoup.parse("<TABLE><TR><TD COLSPAN=\"2147483647\">x</TD><TD ROWSPAN=\"2147483647\">y</TD></TR><TR><TD>a</TD></TR></TABLE>",
                "", Parser.xmlParser()).selectFirst("TABLE");
        var grid = PfExposureParser.grid(table);
        assertThat(grid).hasSize(2);
        assertThat(grid.getFirst().size()).isEqualTo(1001);                      // HTML 표준 상한: colspan 1000 + 1칸
        assertThat(grid.get(1).getFirst()).isEqualTo("a");
    }
}
