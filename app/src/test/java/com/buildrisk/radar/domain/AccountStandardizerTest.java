package com.buildrisk.radar.domain;

import com.buildrisk.radar.domain.account.AccountModels.Basis;
import com.buildrisk.radar.domain.account.AccountModels.MapRule;
import com.buildrisk.radar.domain.account.AccountModels.RawLine;
import com.buildrisk.radar.domain.account.AccountModels.StdAccount;
import com.buildrisk.radar.domain.account.AccountModels.StdValue;
import com.buildrisk.radar.domain.account.AccountStandardizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AccountStandardizerTest {
    static final List<StdAccount> ACCOUNTS = List.of(
            new StdAccount("TOTAL_LIABILITIES", "부채총계", "BS", "POINT", "FIRST", 1),
            new StdAccount("SHORT_BORROWINGS", "단기차입금", "BS", "POINT", "SUM", 2),
            new StdAccount("BONDS", "사채", "BS", "POINT", "SUM", 2),
            new StdAccount("REVENUE", "매출액", "IS", "FLOW", "FIRST", 3),
            new StdAccount("INTEREST_EXPENSE", "이자비용", "IS", "FLOW", "FIRST", 4),
            new StdAccount("OPERATING_CASH_FLOW", "영업활동현금흐름", "CF", "FLOW", "FIRST", 5));
    static final List<MapRule> RULES = List.of(
            new MapRule(1, "TOTAL_LIABILITIES", "BS", "ACCOUNT_ID", "ifrs-full_Liabilities", 10, false),
            new MapRule(2, "TOTAL_LIABILITIES", "BS", "NAME_EXACT", "부채총계", 20, false),
            new MapRule(3, "SHORT_BORROWINGS", "BS", "NAME_REGEX", "^단기차입(금|부채)$", 20, false),
            new MapRule(4, "SHORT_BORROWINGS", "BS", "NAME_REGEX", "^유동성장기(차입금|부채)$", 20, false),
            new MapRule(40, "SHORT_BORROWINGS", "BS", "ACCOUNT_ID", "ifrs-full_CurrentPortionOfLongtermBorrowings", 10, false),
            new MapRule(41, "SHORT_BORROWINGS", "BS", "NAME_REGEX", "^단기금융부채$", 50, false),
            new MapRule(42, "BONDS", "BS", "NAME_REGEX", "^(유동성)?(장기)?(회)?사채$", 20, false),
            new MapRule(43, "SHORT_BORROWINGS", "BS", "NAME_REGEX", "^.*차입(금|부채).*$", 30, false, "CURRENT"),
            new MapRule(5, "REVENUE", null, "ACCOUNT_ID", "ifrs-full_Revenue", 10, false),
            new MapRule(6, "REVENUE", null, "NAME_REGEX", "^(매출액|매출|영업수익)$", 20, false),
            new MapRule(7, "INTEREST_EXPENSE", null, "NAME_REGEX", "^이자비용$", 10, false),
            new MapRule(8, "INTEREST_EXPENSE", "CF", "NAME_REGEX", "^이자(의)?지급(액)?$", 60, true),
            new MapRule(9, "OPERATING_CASH_FLOW", "CF", "NAME_REGEX", "^영업활동(으로인한)?(순)?현금흐름$", 20, false));

    static RawLine line(String sj, int no, String id, String nm, long amt, Long add) {
        return new RawLine(sj, no, id, nm, BigDecimal.valueOf(amt), add == null ? null : BigDecimal.valueOf(add));
    }

    Map<String, StdValue> run(List<RawLine> lines) {
        return new AccountStandardizer(ACCOUNTS, RULES).standardize(lines).values().stream()
                .collect(Collectors.toMap(StdValue::stdCode, v -> v));
    }

    @Test
    void account_id_가_계정명보다_우선하고_계정명은_번호_공백을_무시한다() {
        var v = run(List.of(
                line("BS", 1, "-표준계정코드 미사용-", "Ⅱ. 부채 총계", 999, null),
                line("BS", 2, "ifrs-full_Liabilities", "부채총계(연결)", 500, null)));
        assertThat(v.get("TOTAL_LIABILITIES").amount()).isEqualByComparingTo("500");
        assertThat(v.get("TOTAL_LIABILITIES").basis()).isEqualTo(Basis.POINT);
    }

    @Test
    void SUM_계정은_서로_다른_행을_합하고_같은_행을_두_번_세지_않는다() {
        var v = run(List.of(
                line("BS", 10, null, "단기차입금", 100, null),
                line("BS", 11, null, "유동성장기부채", 30, null),
                line("BS", 12, null, "장기차입금", 999, null)));
        assertThat(v.get("SHORT_BORROWINGS").amount()).isEqualByComparingTo("130");
        assertThat(v.get("SHORT_BORROWINGS").sourceAccount()).isEqualTo("단기차입금 + 유동성장기부채");
    }

    @Test
    void SUM_은_같은_계정명의_순액_액면_중복을_한_번만_세고_한_행은_한_표준계정에만() {
        var v = run(List.of(
                line("BS", 1, "-표준계정코드 미사용-", "단기차입금", 1000, null),
                line("BS", 2, "ifrs-full_CurrentPortionOfLongtermBorrowings", "유동성사채", 998, null),  // 순액 (태그는 차입금)
                line("BS", 3, "-표준계정코드 미사용-", "유동성사채", 1000, null),                         // 액면
                line("BS", 4, "ifrs-full_NoncurrentPortionOfNoncurrentBondsIssued", "사채", 1497, null),
                line("BS", 5, "dart_BondsIssuedNominalValue", "사채", 1500, null)));
        // 유동성사채 순액(2번)은 태그 규칙으로 단기차입금이 먼저 가져가고, 바로 뒤 액면 줄(3번)·사채 액면(5번)은 건너뜀
        assertThat(v.get("SHORT_BORROWINGS").amount()).isEqualByComparingTo("1998");
        assertThat(v.get("BONDS").amount()).isEqualByComparingTo("1497");
    }

    @Test
    void 떨어져_있는_같은_이름_계정은_유동_비유동으로_보고_합산() {
        var v = run(List.of(
                line("BS", 45, null, "회사채", 0, null),
                line("BS", 46, null, "유동성장기차입금", 10, null),
                line("BS", 58, null, "회사채", 1020, null)));
        assertThat(v.get("BONDS").amount()).isEqualByComparingTo("1020");
        assertThat(v.get("BONDS").sourceAccount()).isEqualTo("회사채");
    }

    @Test
    void 구간_규칙은_유동부채_제목_아래_행에만_적용된다() {
        var v = run(List.of(
                line("BS", 1, null, "유동자산", 10, null),
                line("BS", 2, null, "단기대여금", 5, null),
                line("BS", 10, null, "Ⅰ. 유동부채", 900, null),
                line("BS", 11, null, "유동 차입금(사채 포함)", 300, null),
                line("BS", 12, null, "매입채무", 100, null),
                line("BS", 20, null, "비유동부채", 500, null),
                line("BS", 21, null, "차입금등(비유동)", 400, null)));
        assertThat(v.get("SHORT_BORROWINGS").amount()).isEqualByComparingTo("300");
        assertThat(v.get("SHORT_BORROWINGS").sourceAccount()).isEqualTo("유동 차입금(사채 포함)");
    }

    @Test
    void 대체_규칙은_본_규칙에_걸린_행이_없을_때만() {
        var v = run(List.of(line("BS", 1, null, "단기금융부채", 700, null)));
        assertThat(v.get("SHORT_BORROWINGS").amount()).isEqualByComparingTo("700");
        var both = run(List.of(line("BS", 1, null, "단기차입금", 100, null), line("BS", 2, null, "단기금융부채", 700, null)));
        assertThat(both.get("SHORT_BORROWINGS").amount()).isEqualByComparingTo("100");
    }

    @Test
    void 손익은_당기누적금액을_쓰고_IS_가_CIS_보다_우선한다() {
        var v = run(List.of(
                line("CIS", 1, "ifrs-full_Revenue", "매출액", 70, 150L),
                line("IS", 1, "ifrs-full_Revenue", "매출액", 60, 140L)));
        assertThat(v.get("REVENUE").amount()).isEqualByComparingTo("140");
        assertThat(v.get("REVENUE").basis()).isEqualTo(Basis.CUM);
        assertThat(v.get("REVENUE").sourceSjDiv()).isEqualTo("IS");
    }

    @Test
    void 이자비용이_손익계산서에_없으면_현금흐름표_이자의지급을_절댓값으로_대신한다() {
        var v = run(List.of(line("CF", 5, null, "이자의 지급", -42, null)));
        assertThat(v.get("INTEREST_EXPENSE").amount()).isEqualByComparingTo("42");
        assertThat(v.get("INTEREST_EXPENSE").sourceSjDiv()).isEqualTo("CF");
    }

    @Test
    void 매핑되지_않은_표준계정은_missing_으로_돌려준다() {
        var r = new AccountStandardizer(ACCOUNTS, RULES).standardize(List.of(line("BS", 1, null, "부채총계", 1, null)));
        assertThat(r.missing()).containsExactly("SHORT_BORROWINGS", "BONDS", "REVENUE", "INTEREST_EXPENSE", "OPERATING_CASH_FLOW");
    }

    @ParameterizedTest
    @CsvSource({"'Ⅰ. 유동자산',유동자산", "'1.매출액',매출액", "'(1) 단기차입금',단기차입금", "'자산 총계',자산총계",
            "'매출채권(주석5)',매출채권", "'가. 이자비용',이자비용"})
    void 계정명_정규화(String raw, String expected) {
        assertThat(AccountStandardizer.normalize(raw)).isEqualTo(expected);
    }
}
