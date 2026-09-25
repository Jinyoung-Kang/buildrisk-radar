package com.buildrisk.radar.domain.market;

import java.time.YearMonth;

/**
 * 실거래 수집·집계 기간. 계약월 기준 자료라 최근 달은 신고가 계속 추가되는 중 →
 *   수집: [이번 달 − months, 지난달]   다시 받기: 최근 3개월(늦은 신고·해제 반영)   지표: 두 달 전까지
 * (지표 기준 달을 두 달 전으로 둔 근거는 재수집 때 건수 변화 실측 — docs/VERIFICATION.md)
 */
public record TradeWindow(int fromYm, int toYm, int refreshFromYm, int completeToYm) {
    public static TradeWindow of(YearMonth now, int months) {
        return new TradeWindow(ym(now.minusMonths(Math.max(months, 13))), ym(now.minusMonths(1)), ym(now.minusMonths(3)),
                ym(now.minusMonths(2)));
    }

    public static int ym(YearMonth m) { return m.getYear() * 100 + m.getMonthValue(); }

    public static YearMonth parse(int ym) { return YearMonth.of(ym / 100, ym % 100); }
}
