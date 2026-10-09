package com.buildrisk.radar.filing.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventClassifierTest {
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
}
