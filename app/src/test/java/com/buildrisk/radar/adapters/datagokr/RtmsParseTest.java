package com.buildrisk.radar.adapters.datagokr;

import com.buildrisk.radar.adapters.common.ApiKeyRejectedException;
import com.buildrisk.radar.adapters.common.QuotaExceededException;
import com.buildrisk.radar.adapters.common.UpstreamException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 응답(2026-07 강남구, 키 가림)으로 파싱 · 게이트웨이 오류 해석 */
class RtmsParseTest {
    private static String fixture(String name) throws IOException {
        try (var in = RtmsParseTest.class.getResourceAsStream("/fixtures/rtms/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void 실제_응답을_파싱한다() throws IOException {
        var page = RtmsClient.parse(fixture("page1.xml"));
        assertThat(page.totalCount()).isEqualTo(167);
        assertThat(page.items()).hasSize(6);
        var t = page.items().get(0);
        assertThat(t.aptNm()).isEqualTo("신현대9차");
        assertThat(t.dealAmount()).isEqualTo(627_000L);                  // "627,000" 만원
        assertThat(t.excluUseAr()).isEqualByComparingTo("109.24");
        assertThat(t.dealDate()).isEqualTo(LocalDate.of(2026, 7, 30));
        assertThat(t.cancelled()).isFalse();
        assertThat(t.dealingGbn()).isEqualTo("중개거래");
        assertThat(page.items().get(1).dealingGbn()).isEqualTo("직거래");
    }

    @Test
    void 해제_거래와_빈_값() {
        String xml = """
                <response><header><resultCode>000</resultCode><resultMsg>OK</resultMsg></header><body><items>
                <item><aptNm>신동아</aptNm><buildYear>1992</buildYear><cdealDay>26.07.25</cdealDay><cdealType>O</cdealType>
                <dealAmount>183,500</dealAmount><dealDay>16</dealDay><dealMonth>6</dealMonth><dealYear>2026</dealYear>
                <excluUseAr>39.53</excluUseAr><floor> </floor><umdNm>수서동</umdNm></item>
                </items><totalCount>1</totalCount></body></response>""";
        var t = RtmsClient.parse(xml).items().get(0);
        assertThat(t.cancelled()).isTrue();
        assertThat(t.cancelDate()).isEqualTo(LocalDate.of(2026, 7, 25));
        assertThat(t.floor()).isNull();
        assertThat(t.excluUseAr()).isEqualTo(new BigDecimal("39.53"));
    }

    @Test
    void 외부_엔티티는_처리하지_않는다_XXE() {
        String xxe = """
                <?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <response><header><resultCode>000</resultCode><resultMsg>&x;</resultMsg></header><body><totalCount>0</totalCount></body></response>""";
        assertThatThrownBy(() -> RtmsClient.parse(xxe)).isInstanceOf(UpstreamException.class);
    }

    @Test
    void 게이트웨이_오류_코드를_구분한다() {
        String quota = "<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg><returnAuthMsg>"
                + "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</returnAuthMsg><returnReasonCode>22</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>";
        assertThatThrownBy(() -> DataGoKrGateway.checkGatewayError("RTMS", quota)).isInstanceOf(QuotaExceededException.class);
        String key = quota.replace("22", "30").replace("LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR", "SERVICE_KEY_IS_NOT_REGISTERED_ERROR");
        assertThatThrownBy(() -> DataGoKrGateway.checkGatewayError("RTMS", key)).isInstanceOf(ApiKeyRejectedException.class);
        assertThatThrownBy(() -> DataGoKrGateway.checkGatewayError("RTMS", quota.replace(">22<", ">99<")))
                .isInstanceOf(UpstreamException.class);
        DataGoKrGateway.checkGatewayError("RTMS", "<response/>");   // 정상
    }
}
