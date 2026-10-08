package com.buildrisk.radar.domain.region;

import com.buildrisk.radar.domain.region.SiteAddressResolver.Match;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-026 — 실제 공시 주소 문장 · 실거래 표기('94-1' · '산23-26' · '화원읍 천내리')로 */
class SiteAddressResolverTest {
    // 실거래에서 만든 사전 일부 (법정동 → 지번 → 단지)
    static final Map<String, Map<String, Map<String, String>>> DICT = Map.of(
            "26380", Map.of("감천동", Map.of("94-1", "감천현대"), "괴정동", Map.of()),           // 부산 사하구
            "26320", Map.of("만덕동", Map.of()),                                                // 부산 북구
            "11710", Map.of("가락동", Map.of("171-5", "현대7")),                                  // 서울 송파구
            "11200", Map.of("성수동1가", Map.of("685-260", "성수1가아파트"), "성수동2가", Map.of()),     // 서울 성동구
            "41113", Map.of("당수동", Map.of(), "금곡동", Map.of()),                              // 수원 권선구
            "27710", Map.of("화원읍 천내리", Map.of("123", "천내아파트"), "화원읍 구라리", Map.of(), "다사읍 매곡리", Map.of()),
            "44200", Map.of("염치읍 대동리", Map.of(), "배방읍 휴대리", Map.of()));              // 아산
    static final SiteAddressResolver.Dictionary D = lawd -> DICT.getOrDefault(lawd, Map.of());

    static SiteAddressResolver.Result r(String text, String... lawds) { return SiteAddressResolver.resolve(text, List.of(lawds), D); }

    @Test
    void 동과_지번이_실거래_단지와_맞으면_JIBUN() {
        var x = r("서울특별시 송파구 가락동 171-5번지 일원", "11710");
        assertThat(x.match()).isEqualTo(Match.JIBUN);
        assertThat(x.umd()).isEqualTo("가락동");
        assertThat(x.aptNm()).isEqualTo("현대7");
        assertThat(r("부산광역시 사하구 감천동 94-1", "26380").aptNm()).isEqualTo("감천현대");
    }

    @Test
    void 숫자_가로_끝나는_법정동을_끊지_않음() {
        var x = r("서울 성동구 성수동1가 685-260 일원", "11200");
        assertThat(x.match()).isEqualTo(Match.JIBUN);
        assertThat(x.umd()).isEqualTo("성수동1가");
        assertThat(r("서울특별시 성동구 성수동2가 236-5", "11200").umd()).isEqualTo("성수동2가");
    }

    @Test
    void 동만_맞고_지번이_없거나_단지가_없으면_UMD() {
        var x = r("부산광역시 사하구 괴정동 1300-2 일원", "26380");
        assertThat(x.match()).isEqualTo(Match.UMD);
        assertThat(x.jibun()).isEqualTo("1300-2");
        assertThat(r("서울 송파구 가락동", "11710").match()).isEqualTo(Match.UMD);
    }

    @Test
    void 서로_다른_동이_둘_이상이면_고르지_않고_MULTI() {
        assertThat(r("경기도 수원시 권선구 당수동, 금곡동 일원", "41111", "41113", "41115", "41117").match()).isEqualTo(Match.MULTI);
    }

    @Test
    void 구간_공사는_한쪽에만_거래가_있어도_고르지_않음() {
        // 실제 공시: 북구 만덕동 ~ 해운대구 재송동, 수신면 신풍리 ~ 북면 매송리 (한쪽만 아파트 거래가 있음)
        assertThat(r("부산시 북구 만덕동~해운대구 재송동", "26320").match()).isEqualTo(Match.MULTI);
        var cheonan = (SiteAddressResolver.Dictionary) lawd -> Map.of("북면 매송리", Map.of());
        assertThat(SiteAddressResolver.resolve("충남 천안시 동남구 수신면 신풍리 ~ 북면 매송리", List.of("44131"), cheonan).match())
                .isEqualTo(Match.MULTI);
        // 같은 읍 아래의 리들 → 그 읍 전체
        var x = r("대구광역시 달성군 화원읍 천내리 ~ 구라리 일원", "27710");
        assertThat(x.match()).isEqualTo(Match.UMD);
        assertThat(x.umd()).isEqualTo("화원읍");
        // 지번 목록의 쉼표는 구간이 아님
        assertThat(r("서울특별시 송파구 가락동 171-5,171-6번지 일원", "11710").match()).isEqualTo(Match.JIBUN);
    }

    @Test
    void 리로_끝나는_긴_이름은_리가_아님() {
        assertThat(r("경상북도 구미시 하이테크벨리 국가5산업단지 내", "47190").match()).isEqualTo(Match.NONE);
    }

    @Test
    void 읍면_지역은_리_이름으로_찾고_읍면만_있으면_그_읍면_전체() {
        var x = r("대구광역시 달성군 화원읍 천내리 123 일원", "27710");
        assertThat(x.match()).isEqualTo(Match.JIBUN);
        assertThat(x.umd()).isEqualTo("화원읍 천내리");
        var y = r("충청남도 아산시 염치읍", "44200");
        assertThat(y.match()).isEqualTo(Match.UMD);
        assertThat(y.umd()).isEqualTo("염치읍");                 // 리 전체 — 지번 대조는 하지 않음
        assertThat(y.jibun()).isNull();
        assertThat(r("충남 아산시 염치읍 대동리 일원", "44200").umd()).isEqualTo("염치읍 대동리");   // 리까지 있으면 리
    }

    @Test
    void 동_이름이_있어도_그_이름의_아파트_거래가_없으면_비움() {
        assertThat(r("경기도 이천시 마장면 덕평리 615", "41500").match()).isEqualTo(Match.UMD_NO_TRADE);
        assertThat(r("충청남도 논산시 상월면 석종리 산 23-26외 13필지", "44230").match()).isEqualTo(Match.UMD_NO_TRADE);
    }

    @Test
    void 동_이름이_없으면_NONE_건물_번호는_동이_아님() {
        assertThat(r("경기도 부천시 부광로 22번길 45 일대", "41190").match()).isEqualTo(Match.NONE);
        assertThat(r("충청북도 충주시", "43130").match()).isEqualTo(Match.NONE);
        assertThat(r("서울시 송파구 올림픽로 300 101동", "11710").match()).isEqualTo(Match.NONE);
    }

    @Test
    void 산_지번도_실거래_표기로_맞춤() {
        var dict = (SiteAddressResolver.Dictionary) lawd -> Map.of("하남동", Map.of("산2", "하남산단아파트"));
        assertThat(SiteAddressResolver.resolve("경기도 하남시 하남동 산2번지 일원", List.of("41450"), dict).match()).isEqualTo(Match.JIBUN);
    }
}
