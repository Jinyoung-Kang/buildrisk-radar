package com.buildrisk.radar.adapters.dart;

/** Open DART 메시지 코드 (개발가이드 E17). */
public final class DartStatus {
    public static final String OK = "000";
    public static final String NO_DATA = "013";
    public static final String QUOTA = "020";

    private DartStatus() {}

    /** 키·권한 문제 — 재시도해도 소용없음 */
    public static boolean fatal(String s) {
        return switch (s) {
            case "010", "011", "012", "021", "100", "101", "901" -> true;
            default -> false;
        };
    }
}
