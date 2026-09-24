package com.buildrisk.radar.domain;

import com.buildrisk.radar.domain.account.PeriodKeys;
import com.buildrisk.radar.domain.account.QuarterDeriver;
import com.buildrisk.radar.domain.account.QuarterDeriver.Cum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class QuarterAndPeriodTest {
    static Cum c(long v, String fs) { return new Cum(BigDecimal.valueOf(v), fs); }

    @Test
    void Q2_단독은_반기누적_빼기_Q1_이다() {   // FR-205 수용 기준
        Map<String, Cum> cum = new TreeMap<>(Map.of("2025Q1", c(100, "CFS"), "2025Q2", c(250, "CFS"),
                "2025Q3", c(330, "CFS"), "2025Q4", c(500, "CFS")));
        var q = QuarterDeriver.derive(cum);
        assertThat(q.get("2025Q1")).isEqualByComparingTo("100");
        assertThat(q.get("2025Q2")).isEqualByComparingTo("150");
        assertThat(q.get("2025Q3")).isEqualByComparingTo("80");
        assertThat(q.get("2025Q4")).isEqualByComparingTo("170");
    }

    @Test
    void 직전_누적이_없거나_연결별도가_다르면_분기값을_만들지_않는다() {
        Map<String, Cum> cum = new TreeMap<>(Map.of("2025Q2", c(250, "CFS"), "2025Q3", c(330, "OFS"),
                "2026Q1", c(90, "CFS")));
        var q = QuarterDeriver.derive(cum);
        assertThat(q).containsOnlyKeys("2026Q1");
    }

    @ParameterizedTest
    @CsvSource({"2025,11013,2025Q1", "2025,11012,2025Q2", "2025,11014,2025Q3", "2025,11011,2025Q4"})
    void 보고서코드_기간키(String y, String reprt, String key) {
        assertThat(PeriodKeys.key(y, reprt)).isEqualTo(key);
    }

    @Test
    void 기간키_이동과_제출기한() {
        assertThat(PeriodKeys.previous("2026Q1")).isEqualTo("2025Q4");
        assertThat(PeriodKeys.yearAgo("2026Q2")).isEqualTo("2025Q2");
        assertThat(PeriodKeys.expectedAvailable(2025, "11011")).isEqualTo(LocalDate.of(2026, 4, 3));
        assertThat(PeriodKeys.expectedAvailable(2026, "11012")).isEqualTo(LocalDate.of(2026, 8, 17));
    }
}
