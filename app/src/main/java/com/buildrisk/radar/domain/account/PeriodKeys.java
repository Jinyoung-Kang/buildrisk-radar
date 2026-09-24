package com.buildrisk.radar.domain.account;

import java.time.LocalDate;
import java.util.List;

/**
 * 보고서 코드 ↔ 회계 기간 키 (period_key).
 *   11013 1분기 → Q1 · 11012 반기 → Q2 · 11014 3분기 → Q3 · 11011 사업 → Q4
 */
public final class PeriodKeys {
    public static final List<String> REPRT_CODES = List.of("11013", "11012", "11014", "11011");

    private PeriodKeys() {}

    public static int quarterOf(String reprtCode) {
        return switch (reprtCode) {
            case "11013" -> 1;
            case "11012" -> 2;
            case "11014" -> 3;
            case "11011" -> 4;
            default -> throw new IllegalArgumentException("알 수 없는 보고서 코드: " + reprtCode);
        };
    }

    public static String reprtCodeOf(int quarter) {
        return switch (quarter) {
            case 1 -> "11013";
            case 2 -> "11012";
            case 3 -> "11014";
            case 4 -> "11011";
            default -> throw new IllegalArgumentException("분기는 1~4: " + quarter);
        };
    }

    public static String key(String bsnsYear, String reprtCode) {
        return bsnsYear + "Q" + quarterOf(reprtCode);
    }

    public static String key(int year, int quarter) { return year + "Q" + quarter; }

    public static int year(String periodKey) { return Integer.parseInt(periodKey.substring(0, 4)); }

    public static int quarter(String periodKey) { return periodKey.charAt(5) - '0'; }

    /** 분기 순번 — 연속 분기 판정·차이 계산용 */
    public static int ordinal(String periodKey) { return year(periodKey) * 4 + quarter(periodKey) - 1; }

    public static String fromOrdinal(int ordinal) { return key(ordinal / 4, ordinal % 4 + 1); }

    public static String shift(String periodKey, int quarters) { return fromOrdinal(ordinal(periodKey) + quarters); }

    /** 이전 분기 (같은 회계연도가 아니어도) */
    public static String previous(String periodKey) { return shift(periodKey, -1); }

    /** 전년 같은 분기 */
    public static String yearAgo(String periodKey) { return shift(periodKey, -4); }

    /**
     * 법정 제출기한(+여유 3일)이 지나 DART 에 올라와 있을 것으로 보는 날짜.
     * 분기·반기: 결산 후 45일, 사업보고서: 90일.
     */
    public static LocalDate expectedAvailable(int year, String reprtCode) {
        LocalDate d = switch (reprtCode) {
            case "11013" -> LocalDate.of(year, 5, 15);
            case "11012" -> LocalDate.of(year, 8, 14);
            case "11014" -> LocalDate.of(year, 11, 14);
            case "11011" -> LocalDate.of(year + 1, 3, 31);
            default -> throw new IllegalArgumentException(reprtCode);
        };
        return d.plusDays(3);
    }

    public static String label(String periodKey) {
        return switch (quarter(periodKey)) {
            case 1 -> year(periodKey) + " 1분기";
            case 2 -> year(periodKey) + " 반기";
            case 3 -> year(periodKey) + " 3분기";
            default -> year(periodKey) + " 사업연도";
        };
    }
}
