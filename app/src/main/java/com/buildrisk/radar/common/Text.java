package com.buildrisk.radar.common;

/** 조회 조건 글자 다루기 */
public final class Text {
    private Text() {}

    /** 비었거나 공백뿐이면 null, 아니면 앞뒤 공백을 뺀 값 — SQL 의 '조건 없음'(cast(:x AS text) IS NULL)으로 넘기기 위해 */
    public static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
