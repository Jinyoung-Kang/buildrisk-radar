package com.buildrisk.radar.domain.region;

/** 기준 시군구 (ref.region) — 매칭에 필요한 열만 */
public record RegionRef(String regionCd, String name, String sidoName, int level, String parentCd) {
    /** 화면 단위(level 2) 코드 — 일반구면 상위 시 */
    public String displayCd() { return level == 3 && parentCd != null ? parentCd : regionCd; }
}
