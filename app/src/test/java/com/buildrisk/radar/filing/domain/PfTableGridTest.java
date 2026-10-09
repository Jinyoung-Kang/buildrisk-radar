package com.buildrisk.radar.filing.domain;

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

    @Test
    void 줄_너비나_전체_칸_수가_터무니없는_표는_펼치지_않고_건너뛴다() {
        // 칸 하나의 병합 상한만으로는 부족 — colspan=1000 칸 10만 개(약 2.2 MB, 원문 상한 안)면 한 줄이 1억 칸, 큰 rowspan 에
        // 빈 줄을 이어 붙여도 줄 수 × 너비로 같은 일 (VERIFICATION 155). 여기서는 시험 JVM 이 견딜 만큼만 키움
        String wide = "<TABLE><TR>" + "<TD COLSPAN=\"1000\">x</TD>".repeat(3_000) + "</TR><TR><TD>a</TD></TR></TABLE>";
        String tall = "<TABLE><TR><TD ROWSPAN=\"65534\" COLSPAN=\"1000\">x</TD></TR>" + "<TR></TR>".repeat(1_000) + "</TABLE>";
        for (String html : java.util.List.of(wide, tall)) {
            var table = Jsoup.parse(html, "", Parser.xmlParser()).selectFirst("TABLE");
            assertThat(PfExposureParser.grid(table)).isNull();
        }
    }
}

