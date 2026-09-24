package com.buildrisk.radar.domain;

import com.buildrisk.radar.domain.disclosure.EventClassifier;
import com.buildrisk.radar.domain.universe.UniversePolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClassifierAndUniverseTest {
    static final EventClassifier C = new EventClassifier(List.of(
            new EventClassifier.EventType("REHAB", "회생", List.of("회생절차", "기업구조개선", "워크아웃")),
            new EventClassifier.EventType("DEFAULT", "부도", List.of("부도발생", "기한의이익상실")),
            new EventClassifier.EventType("SUSPENSION", "거래정지", List.of("매매거래정지")),
            new EventClassifier.EventType("AUDIT_OPINION", "감사의견", List.of("의견거절", "한정의견")),
            new EventClassifier.EventType("CONTRACT", "수주", List.of("단일판매ㆍ공급계약")),
            new EventClassifier.EventType("PERIODIC", "정기", List.of("분기보고서", "사업보고서"))),
            List.of("정지해제", "지정해제", "액면병합", "변경상장"));

    @ParameterizedTest   // FR-302 분류 사전 테스트
    @CsvSource({
            "'[기재정정]단일판매ㆍ공급계약체결',CONTRACT",
            "'분기보고서 (2026.06)',PERIODIC",
            "'기업구조개선(워크아웃)신청',REHAB",
            "'주권매매거래정지(상장적격성 실질심사)',SUSPENSION",
            "'[첨부정정]감사보고서제출(의견거절)',AUDIT_OPINION",
            "'기한의 이익 상실',DEFAULT",
            "'임원ㆍ주요주주특정증권등소유상황보고서',OTHER",
            "'주권매매거래정지해제              (액면병합 주권 변경상장)',OTHER",
            "'관리종목지정해제',OTHER",
            "'[기재정정]회생절차개시결정',REHAB"})
    void 보고서명_분류(String reportNm, String expected) {
        assertThat(C.classify(reportNm).eventType()).isEqualTo(expected);
    }

    @ParameterizedTest   // FR-103
    @CsvSource(value = {
            "00000001,000001,Y,41221,true,INDUTY:41",
            "00000002,000002,K,42110,false,INDUTY_OUT",
            "00000003,NULL,NULL,41221,false,UNLISTED",
            "00000004,000004,Y,NULL,false,NO_INDUTY",
            "00000005,000005,Y,64992,true,MANUAL_INCLUDE",
            "00000006,000006,Y,41221,false,MANUAL_EXCLUDE",
            "00000007,000007,E,41112,false,NOT_LISTED_CLS:E",
            "00000008,000008,N,41112,false,NOT_LISTED_CLS:N"}, nullValues = "NULL")
    void 유니버스_판정(String corp, String stock, String cls, String induty, boolean target, String reason) {
        var p = new UniversePolicy(List.of("41"), Map.of("00000005", true, "00000006", false));
        var d = p.decide(corp, stock, cls, induty);
        assertThat(d.target()).isEqualTo(target);
        assertThat(d.reason()).isEqualTo(reason);
    }
}
