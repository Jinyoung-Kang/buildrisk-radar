package com.buildrisk.radar.domain.backtest;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * 판정을 '알 수 있게 된 날' (point-in-time, ADR-017 · ADR-023) — 근거 공시 접수번호(앞 8자리 = 접수일) 중 가장 늦은 날.
 * 그날 전에는 조건을 알 수 없으므로 백테스트 · 성적표 모두 이 날짜를 씁니다.
 */
public final class PointInTime {
    private static final Pattern RCEPT = Pattern.compile("^\\d{14}$");
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private PointInTime() {}

    /** asOf 가 접수번호(공시 규칙)면 그 자체도 후보 */
    public static LocalDate of(String asOf, Iterable<String> rceptNos) {
        LocalDate best = asOf != null && RCEPT.matcher(asOf).matches() ? LocalDate.parse(asOf.substring(0, 8), YMD) : null;
        for (String no : rceptNos) {
            if (no == null || !RCEPT.matcher(no).matches()) continue;
            LocalDate d = LocalDate.parse(no.substring(0, 8), YMD);
            if (best == null || d.isAfter(best)) best = d;
        }
        return best;
    }
}
