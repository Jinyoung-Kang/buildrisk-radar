package com.buildrisk.radar.domain;

import com.buildrisk.radar.domain.region.AddressRegionMatcher;
import com.buildrisk.radar.domain.region.AddressRegionMatcher.Match;
import com.buildrisk.radar.domain.region.RegionAliases;
import com.buildrisk.radar.domain.region.RegionRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-016 — 실제 공시의 '판매ㆍ공급지역' 표기(첫 적재에서 UNKNOWN·SIDO 로 남았던 사례 포함) */
class AddressRegionMatcherTest {
    static final List<RegionRef> REGIONS = List.of(
            new RegionRef("26380", "사하구", "부산광역시", 2, null),
            new RegionRef("26320", "북구", "부산광역시", 2, null),
            new RegionRef("30140", "중구", "대전광역시", 2, null),
            new RegionRef("41220", "평택시", "경기도", 2, null),
            new RegionRef("41460", "용인시", "경기도", 2, null),
            new RegionRef("41590", "화성시", "경기도", 2, null),
            new RegionRef("43110", "청주시", "충청북도", 2, null),
            new RegionRef("43113", "청주시 흥덕구", "충청북도", 3, "43110"),
            new RegionRef("36110", "세종특별자치시", "세종특별자치시", 2, null),
            new RegionRef("42820", "고성군", "강원특별자치도", 2, null),
            new RegionRef("48820", "고성군", "경상남도", 2, null),
            new RegionRef("28260", "서해구", "인천광역시", 2, null),
            new RegionRef("28110", "중구", "인천광역시", 2, null));
    static final RegionAliases ALIASES = new RegionAliases(
            Map.of("부산", List.of("부산광역시"), "대전", List.of("대전광역시"), "경기", List.of("경기도"), "충북", List.of("충청북도"),
                    "세종", List.of("세종특별자치시"), "인천", List.of("인천광역시"), "강원", List.of("강원특별자치도"), "경남", List.of("경상남도")),
            Map.of(), Map.of(), List.of());
    final AddressRegionMatcher m = new AddressRegionMatcher(REGIONS, ALIASES);

    @ParameterizedTest(name = "{0} → {1} {2}")
    @CsvSource(delimiter = '|', value = {
            "부산광역시 사하구 감천동 94-1번지 일원|SIGUNGU|26380",
            "부산시 북구 만덕동~해운대구 재송동|SIGUNGU|26320",
            "대전시 중구 용두동 56-53 일원|SIGUNGU|30140",
            "경기도 용인시~경기도 화성시 일원|SIGUNGU|41460",
            "경기 용인|SIGUNGU|41460",
            "충청북도 청주|SIGUNGU|43110",
            "청주시 흥덕구 봉명동 산26번지 일원|SIGUNGU|43110",
            "평택시 고덕면 여염리 1672 일원|SIGUNGU|41220",
            "세종특별자치시 전동면 봉대리~충남 천안시|SIGUNGU|36110",
            "고성군 토성면 일원|UNKNOWN|",
            "인천광역시 서구 석남동 석남역|SIDO|",
            "경기도|SIDO|",
            "Alger, Algeria|OVERSEAS|",
            "싱가폴 Tekong Island|OVERSEAS|",
            "삼성전자(주) 평택캠퍼스|UNKNOWN|",
            "-|NONE|"})
    void 주소를_시군구로(String text, Match expected, String regionCd) {
        var r = m.match(text);
        assertThat(r.match()).isEqualTo(expected);
        assertThat(r.regionCd()).isEqualTo(regionCd == null || regionCd.isBlank() ? null : regionCd);
    }
}
