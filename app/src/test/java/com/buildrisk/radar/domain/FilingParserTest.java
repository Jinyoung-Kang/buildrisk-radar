package com.buildrisk.radar.domain;

import com.buildrisk.radar.domain.filing.FilingParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-016 — 실제 공시 원문 5건(유가 체결 · 코스닥 정정 · 해지 · PF 보증 · 보증 정정)으로 구조화 검증 */
class FilingParserTest {
    private static String doc(String name) throws IOException {
        try (var in = FilingParserTest.class.getResourceAsStream("/fixtures/dart/filings/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void 유가증권_서식_공사수주() throws IOException {
        var c = FilingParser.contract(doc("contract_kospi_20260923800033.html"));
        assertThat(c.kind()).isEqualTo("공사수주");
        assertThat(c.name()).isEqualTo("감천2구역 주택재개발정비사업");
        assertThat(c.amount()).isEqualByComparingTo("882824000000");
        assertThat(c.recentRevenue()).isEqualByComparingTo("31062912168499");
        assertThat(c.pctOfRevenue()).isEqualByComparingTo("2.84");
        assertThat(c.counterparty()).isEqualTo("감천2구역 주택재개발정비사업조합");
        assertThat(c.regionText()).isEqualTo("부산광역시 사하구 감천동 94-1번지 일원");
        assertThat(c.startDate()).isNull();                                   // '-'
        assertThat(c.contractDate()).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(c.correction()).isNull();
    }

    @Test
    void 코스닥_정정_서식은_본문_값과_정정_대상일을_읽는다() throws IOException {
        var c = FilingParser.contract(doc("contract_correction_20260923900468.html"));
        assertThat(c.name()).isEqualTo("대광새마을금고 골프연습장 신축공사");
        assertThat(c.amount()).isEqualByComparingTo("7681818182");          // '계약금액 총액(원)'
        assertThat(c.recentRevenue()).isEqualByComparingTo("56667089060");  // 계약상대방의 최근 매출액이 아니라 계약내역의 값
        assertThat(c.pctOfRevenue()).isEqualByComparingTo("13.56");
        assertThat(c.counterparty()).isEqualTo("대광새마을금고");
        assertThat(c.endDate()).isEqualTo(LocalDate.of(2026, 12, 31));      // 정정 후 값 (정정표의 '정정전' 값이 아님)
        assertThat(c.contractDate()).isEqualTo(LocalDate.of(2025, 10, 14));
        assertThat(c.correction().originalDate()).isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(c.correction().reason()).isEqualTo("계약기간 변경");
    }

    @Test
    void 계약_해지() throws IOException {
        var t = FilingParser.termination(doc("termination_20260722800676.html"));
        assertThat(t.name()).isEqualTo("부산 하단1구역 재건축정비사업");
        assertThat(t.amount()).isEqualByComparingTo("116787990000");
        assertThat(t.reason()).isEqualTo("발주처의 계약 해지 통보");
        assertThat(t.terminatedOn()).isEqualTo(LocalDate.of(2026, 7, 22));
        assertThat(t.originalDate()).isEqualTo(LocalDate.of(2024, 5, 31));
    }

    @Test
    void 채무보증과_PF_유형표() throws IOException {
        var g = FilingParser.guarantee(doc("guarantee_pf_20260918800184.html"));
        assertThat(g.debtor()).isEqualTo("피엠씨앤컴퍼니 주식회사");
        assertThat(g.creditor()).isEqualTo("플랜업유제이 주식회사");
        assertThat(g.amount()).isEqualByComparingTo("135000000000");
        assertThat(g.equity()).isEqualByComparingTo("3212755613173");
        assertThat(g.pctOfEquity()).isEqualByComparingTo("4.2");
        assertThat(g.totalBalance()).isEqualByComparingTo("2340556580911");
        assertThat(g.startDate()).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(g.endDate()).isEqualTo(LocalDate.of(2026, 12, 21));
        assertThat(g.decisionDate()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(g.pf()).hasSize(1);
        assertThat(g.pf().get(0).pfType()).isEqualTo("기타 PF Loan");
        assertThat(g.pfAmount()).isEqualByComparingTo("135000000000");
    }

    @Test
    void 보증_정정은_정정_후_본문을_읽는다() throws IOException {
        var g = FilingParser.guarantee(doc("guarantee_correction_20260918800382.html"));
        assertThat(g.amount()).isEqualByComparingTo("30367200000");         // 정정 후
        assertThat(g.totalBalance()).isEqualByComparingTo("1027764839537");
        assertThat(g.endDate()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(g.correction().originalDate()).isEqualTo(LocalDate.of(2026, 3, 23));
        assertThat(g.pf()).isEmpty();
        assertThat(g.pfAmount()).isNull();
    }

    @Test
    void 자율공시_정정_서식은_세부내용과_계약일을_읽는다() throws IOException {
        // 실데이터 첫 적재에서 PARTIAL 로 남은 21건 중 하나 → 파서 v2 (원문 재파싱, 호출 없음)
        var c = FilingParser.contract(doc("contract_voluntary_correction_20260910800167.html"));
        assertThat(c.kind()).isEqualTo("공사수주");
        assertThat(c.name()).isEqualTo("순천 벌교-주암(3-1공구) 도로확장공사");
        assertThat(c.amount()).isEqualByComparingTo("102561600000");        // 정정 후
        assertThat(c.regionText()).startsWith("전남 순천시");
        assertThat(c.contractDate()).isEqualTo(LocalDate.of(2019, 6, 19));  // '계약(수주)일'
        assertThat(c.correction().originalDate()).isEqualTo(LocalDate.of(2026, 1, 27));
    }

    @Test
    void 자율공시_해지는_세부물건과_관련공시의_체결일을_읽는다() throws IOException {
        var t = FilingParser.termination(doc("termination_voluntary.html"));
        assertThat(t.name()).isEqualTo("대장~홍대 광역철도 민간투자시설사업 건설공사");
        assertThat(t.amount()).isEqualByComparingTo("289621000000");
        assertThat(t.originalDate()).isEqualTo(LocalDate.of(2024, 6, 28));   // 관련공시 3건 중 '공급계약 체결'
    }

    @Test
    void 보증_주석의_한도_표기와_미사용액() throws IOException {
        // "'5. 채무보증 총 잔액'은 채무보증 한도이며, 미사용잔액이 포함된 수치임 - PF관련 보증한도는 14,203억(미사용한도 47억),
        //  일반채무보증한도는 9,203억(미사용한도 3,359억)" — 한도 합 23,406억 ≈ 총 잔액 23,405.6억 (±1%)
        var g = FilingParser.guarantee(doc("guarantee_pf_20260918800184.html"));
        assertThat(g.balanceIsLimit()).isTrue();
        assertThat(g.unusedLimit()).isEqualByComparingTo("340600000000");
        assertThat(g.usedBalance()).isEqualByComparingTo("1999956580911");
        var plain = FilingParser.guarantee(doc("guarantee_correction_20260918800382.html"));
        assertThat(plain.unusedLimit()).isNull();                            // 주석에 한도별 미사용액 없음 → 비움
    }

    @Test
    void 한도_합이_총_잔액과_맞지_않으면_미사용액을_쓰지_않는다() {
        String text = "채무보증 총 잔액은 약정한도이며, 미사용잔액이 포함된 수치입니다. PF관련 보증한도 : 1,000억원(미사용한도 100억원)";
        assertThat(FilingParser.limitNote(text)).isTrue();
        assertThat(FilingParser.unusedLimit(text, new java.math.BigDecimal("500000000000"))).isNull();   // 1,000억 ≠ 5,000억
        assertThat(FilingParser.unusedLimit(text, new java.math.BigDecimal("100000000000"))).isEqualByComparingTo("10000000000");
    }
}
