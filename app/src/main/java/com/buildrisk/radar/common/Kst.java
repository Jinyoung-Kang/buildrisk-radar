package com.buildrisk.radar.common;

import java.time.LocalDate;
import java.time.ZoneId;

/** 서비스의 기준 시간대 — 수집 한도 · 기준일 · 파일 이름의 날짜는 모두 KST */
public final class Kst {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private Kst() {}

    public static LocalDate today() { return LocalDate.now(ZONE); }
}
