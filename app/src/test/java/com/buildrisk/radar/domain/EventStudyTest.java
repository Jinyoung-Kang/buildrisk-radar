package com.buildrisk.radar.domain;

import com.buildrisk.radar.domain.backtest.EventStudy;
import com.buildrisk.radar.domain.backtest.EventStudy.Event;
import com.buildrisk.radar.domain.backtest.EventStudy.Excluded;
import com.buildrisk.radar.domain.market.StockRepository.Bar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** ADR-017 — 사건 연구: 다음 거래일 진입 · 동일가중 비교군 · 주식수 변동/겹침/미경과 제외 */
class EventStudyTest {
    static final LocalDate D0 = LocalDate.of(2026, 1, 5);

    /** 30 거래일(평일) · 종목 7개: A 는 10일째부터 하루 1% 복리 상승, 나머지는 보합 */
    static List<Bar> bars(Long aShareJumpAtDay) {
        List<Bar> out = new ArrayList<>();
        LocalDate d = D0;
        for (int i = 0; i < 30; i++) {
            while (d.getDayOfWeek().getValue() > 5) d = d.plusDays(1);
            double a = i < 10 ? 100 : 100 * Math.pow(1.01, i - 9);
            long shares = aShareJumpAtDay != null && i >= aShareJumpAtDay ? 2_000_000L : 1_000_000L;
            out.add(new Bar("A00001", d, BigDecimal.valueOf(a), shares, null));
            for (int p = 1; p <= 6; p++) out.add(new Bar("P0000" + p, d, BigDecimal.valueOf(50), 1_000_000L, null));
            d = d.plusDays(1);
        }
        return out;
    }

    static LocalDate tradingDay(int i) {
        LocalDate d = D0;
        int n = -1;
        while (true) {
            if (d.getDayOfWeek().getValue() <= 5 && ++n == i) return d;
            d = d.plusDays(1);
        }
    }

    @Test
    void 다음_거래일_종가에_진입해_h일_뒤_초과수익률() {
        var study = new EventStudy(bars(null));
        var out = study.run(List.of(new Event(1, "R-C01", "A00001", tradingDay(9))), 10);   // 공시일 = 9번째 날 → 10번째 날 진입
        var o = out.get(0);
        assertThat(o.excluded()).isNull();
        assertThat(o.entryDate()).isEqualTo(tradingDay(10));
        assertThat(o.exitDate()).isEqualTo(tradingDay(20));
        assertThat(o.ret()).isCloseTo(Math.pow(1.01, 10) - 1, within(1e-9));
        assertThat(o.bench()).isCloseTo(0.0, within(1e-12));
        assertThat(o.benchSize()).isEqualTo(6);
        assertThat(o.excess()).isCloseTo(o.ret(), within(1e-12));
    }

    @Test
    void 제외_규칙() {
        var study = new EventStudy(bars(15L));                                                  // 15일째 주식수 2배 (분할 등)
        var out = study.run(List.of(
                new Event(1, "R-C01", "A00001", tradingDay(9)),                                  // 창 안에 주식수 변동
                new Event(2, "R-C02", "A00001", tradingDay(25)),                                 // 10일이 아직 안 지남
                new Event(3, "R-C03", "ZZZZZZ", tradingDay(1))), 10);                            // 시세 없음
        assertThat(out).extracting(EventStudy.Outcome::excluded)
                .containsExactly(Excluded.NO_PRICE, Excluded.SHARE_CHANGE, Excluded.HORIZON_NOT_ELAPSED);
    }

    @Test
    void 같은_규칙_종목의_겹치는_사건은_앞의_것만() {
        var study = new EventStudy(bars(null));
        var out = study.run(List.of(new Event(1, "R-C01", "A00001", tradingDay(2)),
                new Event(2, "R-C01", "A00001", tradingDay(5)),                                 // 겹침
                new Event(3, "R-C02", "A00001", tradingDay(5))), 10);                           // 다른 규칙은 따로
        assertThat(out).extracting(EventStudy.Outcome::excluded).containsExactly(null, Excluded.OVERLAP, null);
        var stats = EventStudy.stats(out);
        assertThat(stats.events()).isEqualTo(3);
        assertThat(stats.used()).isEqualTo(2);
        assertThat(stats.excluded()).containsEntry(Excluded.OVERLAP, 1);
        assertThat(stats.negativeShare()).isZero();
    }

    @Test
    void 비교_기준_분포는_신호와_무관한_창() {
        var base = new EventStudy(bars(null)).baseline(10);
        assertThat(base).hasSize(7 * 2);                                                          // 종목 7 × (30일 / 10일 간격, 마지막 창 제외)
    }
}
