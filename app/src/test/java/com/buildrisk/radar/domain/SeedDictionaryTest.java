package com.buildrisk.radar.domain;

import com.buildrisk.radar.common.seed.SeedCatalog;
import com.buildrisk.radar.filing.domain.EventClassifier;
import com.buildrisk.radar.insight.domain.CreditEventRule;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 저장소의 실제 seed 사전으로 검증 — 인라인 사전 테스트로는 사전 파일에서 빠진 키워드를 잡지 못했음 */
class SeedDictionaryTest {
    static final SeedCatalog SEED = SeedCatalog.at(Path.of("../seed"));
    static final EventClassifier CLASSIFIER = SEED.eventClassifier();
    static final CreditEventRule CREDIT = SEED.creditEventRule();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // 태영건설 2023-12-28 워크아웃 — 기촉법상 공시 이름. 예전 사전으로는 OTHER (R-C04 가 놓침)
            "채권은행등의관리절차개시신청 | REHAB",
            "주요사항보고서(채권은행등의관리절차개시) | REHAB",
            "주요사항보고서(회생절차개시신청) | REHAB",
            "주요사항보고서(부도발생) | DEFAULT",
            "반기검토의견부적정,의견거절또는완전자본잠식사실발생 | AUDIT_OPINION",
            "매매거래정지및정지해제(중요내용공시) | OTHER",
            "채권은행등의관리절차해제 | OTHER",                                      // 워크아웃 졸업 — 부실 신호 아님
            "회생절차종결결정 | OTHER",
    })
    void 실제_공시_제목_분류(String reportNm, String expected) {
        assertThat(CLASSIFIER.classify(reportNm).eventType()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "채권은행등의관리절차개시신청 | true",                                   // 태영건설 워크아웃
            "주요사항보고서(회생절차개시신청) | true",
            "주권매매거래정지              (회생절차개시신청) | true",                 // KD — 공백이 여러 칸
            "주요사항보고서(부도발생) | true",
            "반기검토의견부적정,의견거절또는완전자본잠식사실발생 | true",
            "회생절차개시신청(종속회사의주요경영사항) | false",                         // 자회사 사안
            "파산신청 | false",                                                       // 채권자의 파산신청 (신원종합개발 — 기각)
            "기타주요경영사항              (회생절차 개시신청 취하 허가 결정) | false",  // 취하 안내
            "조회공시요구(풍문또는보도)(채권자에 의한 파산신청설) | false",
            "[기재정정]회생절차개시결정 | false",                                     // 정정은 원 공시보다 늦음
            "상장채권기한의이익상실 | false",                                         // 사채 단위 사안 — 부실 사건 정의 밖
            "채권은행등의관리절차해제 | false",
    })
    void 성적표의_부실_사건_정의(String reportNm, boolean expected) {
        assertThat(CREDIT.matches(reportNm)).isEqualTo(expected);
    }
}
