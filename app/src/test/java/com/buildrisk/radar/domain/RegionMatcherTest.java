package com.buildrisk.radar.domain;

import com.buildrisk.radar.domain.region.RegionAliases;
import com.buildrisk.radar.domain.region.RegionMatcher;
import com.buildrisk.radar.domain.region.RegionMatcher.Method;
import com.buildrisk.radar.domain.region.RegionRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RegionMatcherTest {
    static final List<RegionRef> REFS = List.of(
            new RegionRef("11110", "종로구", "서울특별시", 2, null),
            new RegionRef("26110", "중구", "부산광역시", 2, null),
            new RegionRef("11140", "중구", "서울특별시", 2, null),
            new RegionRef("41110", "수원시", "경기도", 2, null),
            new RegionRef("41111", "수원시 장안구", "경기도", 3, "41110"),
            new RegionRef("41170", "안양시", "경기도", 2, null),
            new RegionRef("41171", "안양시 만안구", "경기도", 3, "41170"),
            new RegionRef("29140", "서구", "광주광역시", 2, null),
            new RegionRef("46110", "목포시", "전라남도", 2, null),
            new RegionRef("36110", "세종시", "세종특별자치시", 2, null),
            new RegionRef("42820", "고성군", "강원특별자치도", 2, null),
            new RegionRef("48820", "고성군", "경상남도", 2, null),
            new RegionRef("28155", "영종구", "인천광역시", 2, null));
    static final RegionAliases ALIASES = new RegionAliases(
            Map.of("서울", List.of("서울특별시"), "부산", List.of("부산광역시"), "경기", List.of("경기도"), "인천", List.of("인천광역시"),
                    "강원", List.of("강원특별자치도", "강원도"), "경남", List.of("경상남도"),
                    "전남광주", List.of("광주광역시", "전라남도"), "세종", List.of("세종특별자치시")),
            Map.of(), Map.of("세종시", "세종특별자치시"),
            List.of(new RegionAliases.Manual("KOSIS", "인천 > 영종구", null, "신설 구"),
                    new RegionAliases.Manual("KOSIS", "인천 > 검단구", null, "신설 구 — 경계 미반영"),
                    new RegionAliases.Manual("RONE", "경남 > 고성군", "42820", "일부러 틀린 수동 지정")));
    final RegionMatcher m = new RegionMatcher(REFS, ALIASES);

    @Test
    void 시도_약칭과_이름으로_찾고_같은_이름은_시도로_가른다() {
        assertThat(m.match("KOSIS", "서울", List.of("중구")).region().regionCd()).isEqualTo("11140");
        assertThat(m.match("KOSIS", "부산", List.of("중구")).region().regionCd()).isEqualTo("26110");
        assertThat(m.match("KOSIS", "강원", List.of("고성군")).region().regionCd()).isEqualTo("42820");
        assertThat(m.match("KOSIS", "경남", List.of("고성군")).region().regionCd()).isEqualTo("48820");
    }

    @Test
    void 통합_시도_이름은_후보_시도_모두에서_찾는다() {
        assertThat(m.match("KOSIS", "전남광주", List.of("서구")).region().regionCd()).isEqualTo("29140");
        assertThat(m.match("KOSIS", "전남광주", List.of("목포시")).region().regionCd()).isEqualTo("46110");
    }

    @Test
    void R_ONE_전체경로의_일반구와_시_단위() {
        assertThat(m.match("RONE", "경기", List.of("경부1권", "안양시", "만안구")).region().regionCd()).isEqualTo("41171");
        assertThat(m.match("RONE", "경기", List.of("경부1권", "안양시")).region().regionCd()).isEqualTo("41170");
        assertThat(m.match("RONE", "경기", List.of("경부1권")).method()).isEqualTo(Method.AGGREGATE);
    }

    @Test
    void SGIS_시_구_결합이름과_세종_별칭() {
        assertThat(m.match("SGIS", "경기", List.of("수원시 장안구")).region().displayCd()).isEqualTo("41110");
        assertThat(m.match("KOSIS", "세종", List.of("세종시")).region().regionCd()).isEqualTo("36110");
        assertThat(m.match("RONE", "세종", List.of()).region().regionCd()).isEqualTo("36110");
    }

    @Test
    void 수동_항목은_이름으로_못_찾을_때_사유로만_쓰고_regionCd_가_있으면_우선한다() {
        assertThat(m.match("KOSIS", "인천", List.of("영종구")).region().regionCd()).isEqualTo("28155");   // 경계에 있으면 매핑
        var missing = m.match("KOSIS", "인천", List.of("검단구"));
        assertThat(missing.mapped()).isFalse();
        assertThat(missing.note()).isEqualTo("신설 구 — 경계 미반영");
        var forced = m.match("RONE", "경남", List.of("고성군"));
        assertThat(forced.method()).isEqualTo(Method.MANUAL);
        assertThat(forced.region().regionCd()).isEqualTo("42820");
    }

    @Test
    void 모르는_이름과_시도() {
        assertThat(m.match("KOSIS", "서울", List.of("없는구")).method()).isEqualTo(Method.UNMAPPED);
        assertThat(m.match("KOSIS", "화성", List.of("무슨구")).note()).contains("시도");
        assertThat(m.match("RONE", "전국", List.of()).method()).isEqualTo(Method.AGGREGATE);
        assertThat(m.match("RONE", "5대광역시", List.of()).method()).isEqualTo(Method.AGGREGATE);
    }
}
