package com.buildrisk.radar.adapters.applyhome;

import com.buildrisk.radar.adapters.common.ApiKeyRejectedException;
import com.buildrisk.radar.adapters.common.QuotaExceededException;
import com.buildrisk.radar.adapters.common.UpstreamException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 응답(2026-10-19 청약홈, 키는 응답에 없음)으로 파싱 · 오류 봉투 해석 (ADR-032) */
class ApplyhomeParseTest {
    private static final ObjectMapper M = JsonMapper.builder().build();

    private static String fixture(String name) throws IOException {
        try (var in = ApplyhomeParseTest.class.getResourceAsStream("/fixtures/applyhome/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void APT_공고를_파싱한다() throws IOException {
        var page = ApplyhomeClient.parseNotices(M, fixture("apt_notice.json"));
        assertThat(page.matchCount()).isEqualTo(5);
        assertThat(page.items()).hasSize(5);
        var n = page.items().get(1);
        assertThat(n.houseManageNo()).isEqualTo("2026000458");
        assertThat(n.houseNm()).isEqualTo("산곡역자이힐스테이트앤하늘채");
        assertThat(n.builderName()).isEqualTo("지에스건설(주), 현대건설(주), 코오롱글로벌(주)");
        assertThat(n.totSupplyHh()).isEqualTo(1289);
        assertThat(n.noticeDate()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(n.moveinYm()).isEqualTo("202912");
        assertThat(n.flags().redevelopment()).isTrue();                     // IMPRMN_BSNS_AT = Y (산곡6 재개발)
        assertThat(n.address()).startsWith("인천광역시 부평구 산곡동");
        assertThat(n.raw()).contains("\"HOUSE_MANAGE_NO\":\"2026000458\"");
        assertThat(page.items().get(4).builderName()).isNull();             // 시공사 빈 공고 (27/1,264건)
    }

    @Test
    void 주택형은_만원을_원으로_특별공급_세부는_원_필드로() throws IOException {
        var page = ApplyhomeClient.parseModels(M, fixture("apt_model.json"));
        assertThat(page.items()).hasSize(9);
        var m = page.items().get(0);
        assertThat(m.houseTy()).isEqualTo("050.2600A");
        assertThat(m.supplyArea()).isEqualByComparingTo("68.27");
        assertThat(m.topAmountWon()).isEqualByComparingTo(new BigDecimal("556000000"));   // "55600" 만원
        assertThat(m.supplyHh() + m.specialHh()).isEqualTo(112);
        // 공급규모(1,289) = 주택형의 일반 + 특별공급 합 — 조합원분은 공고 세대에 없음 (2026-10 실측 6건 모두 같음)
        assertThat(page.items().stream().mapToInt(x -> x.supplyHh() + x.specialHh()).sum()).isEqualTo(1289);
        assertThat(m.specialDetail()).containsKey("NWWDS_HSHLDCO");
    }

    @Test
    void 잔여세대_공고를_파싱한다() throws IOException {
        var page = ApplyhomeClient.parseRemainders(M, fixture("remainder.json"));
        assertThat(page.items()).extracting(r -> r.houseSecd()).containsExactly("04", "04", "06");
        var r = page.items().get(1);
        assertThat(r.houseNm()).isEqualTo("올 뉴 챔피언스시티 1차");
        assertThat(r.totSupplyHh()).isEqualTo(729);
        assertThat(r.receiptBegin()).isEqualTo(LocalDate.of(2026, 10, 20));
    }

    @Test
    void 데이터셋_전체가_0건인_응답은_정상_0건이_아니라_멈춤() throws IOException {
        // 2026-10-20 실측: 같은 키 · 같은 요청이 몇 시간 전 totalCount 2,907 → 200 OK · totalCount 0 · data [] (일일 트래픽 초과로 추정, 원인 미확인).
        // '그 달 공고 0건'으로 저장하면 원장이 받은 달로 남아 다시 받지 않고, 주택형은 0개로 끝난 것이 됨 (VERIFICATION 216)
        assertThatThrownBy(() -> ApplyhomeClient.parseNotices(M, fixture("empty_dataset.json"))).isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("totalCount");
        assertThatThrownBy(() -> ApplyhomeClient.parseModels(M, fixture("empty_dataset.json"))).isInstanceOf(QuotaExceededException.class);
        // 조건에 맞는 것만 0건(데이터셋은 있음)은 정상
        assertThat(ApplyhomeClient.parseModels(M, "{\"currentCount\":0,\"data\":[],\"matchCount\":0,\"totalCount\":14869}").items()).isEmpty();
    }

    @Test
    void 날짜는_두_형식만_받고_그_밖은_비운다() {
        var n = M.readTree("{\"a\":\"2026-10-19\",\"b\":\"20261019\",\"c\":\"2026.10.19\",\"d\":\"\",\"e\":null,\"m\":\"202613\",\"y\":\"Y\",\"x\":\"-\"}");
        assertThat(ApplyhomeClient.date(n, "a")).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(ApplyhomeClient.date(n, "b")).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(ApplyhomeClient.date(n, "c")).isNull();
        assertThat(ApplyhomeClient.date(n, "d")).isNull();
        assertThat(ApplyhomeClient.date(n, "e")).isNull();
        assertThat(ApplyhomeClient.ym(n, "m")).isNull();                     // 13월
        assertThat(ApplyhomeClient.yn(n, "y")).isTrue();
        assertThat(ApplyhomeClient.yn(n, "x")).isNull();
    }

    @Test
    void 오류_봉투는_키_문제_한도_일시_오류로_나눈다() throws IOException {
        assertThatThrownBy(() -> ApplyhomeClient.parseNotices(M, fixture("error_key.json")))
                .isInstanceOf(ApiKeyRejectedException.class).hasMessageContaining("인증키");
        assertThatThrownBy(() -> ApplyhomeClient.parseNotices(M, "{\"code\":-10,\"msg\":\"일일 트래픽을 초과했습니다\"}"))
                .isInstanceOf(QuotaExceededException.class);
        assertThatThrownBy(() -> ApplyhomeClient.parseNotices(M, "<html>Bad Gateway</html>")).isInstanceOf(UpstreamException.class);
        assertThatThrownBy(() -> ApplyhomeClient.parseNotices(M, "{\"data\":[{\"HOUSE_NM\":\"x\"}],\"matchCount\":1}"))
                .isInstanceOf(UpstreamException.class).hasMessageContaining("HOUSE_MANAGE_NO");
    }
}
