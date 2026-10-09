package com.buildrisk.radar.filing.domain;

import com.buildrisk.radar.filing.domain.PfExposureParser.Scope;
import com.buildrisk.radar.filing.domain.PfExposureParser.Site;
import com.buildrisk.radar.filing.domain.PfExposureParser.Status;
import com.buildrisk.radar.filing.domain.PfExposureParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 정기보고서(2026 반기 등)의 「우발부채 등에 관한 사항」 PF 부분 fixture 로 검증 (ADR-024).
 * 기대값은 보고서 본문의 서술(예: 태영건설 "총 2,354,379백만원의 신용보강")과 맞춘 값.
 */
class PfExposureParserTest {
    static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    static PfExposureParser.Result parse(String name) {
        try (var in = PfExposureParserTest.class.getResourceAsStream("/fixtures/pf/" + name + ".xml")) {
            return PfExposureParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static BigDecimal m(long million) { return BigDecimal.valueOf(million).multiply(MILLION); }

    static BigDecimal total(PfExposureParser.Result r, Scope scope) {
        return r.summary().stream().filter(s -> s.total() && s.scope() == scope).findFirst().orElseThrow().guarantee();
    }

    @Test
    void 태영건설_단독_컨소시엄_꼬리표로_범위를_나누고_회사_노출은_단독과_컨소시엄_회사분의_합() {
        var r = parse("taeyoung-2026q2");
        assertThat(r.status()).isEqualTo(Status.PARSED);
        assertThat(total(r, Scope.SOLE)).isEqualByComparingTo(m(1_628_629));
        assertThat(total(r, Scope.CONSORTIUM_ALL)).isEqualByComparingTo(m(1_029_197));      // 컨소시엄 전체분은 회사 노출이 아님
        assertThat(total(r, Scope.CONSORTIUM_SHARE)).isEqualByComparingTo(m(725_750));
        assertThat(r.totals().guarantee()).isEqualByComparingTo(m(2_354_379));               // 본문 "총 2,354,379백만원의 신용보강"
        assertThat(r.totals().due().within12m()).isEqualByComparingTo(m(1_312_155));
        // 세부 내역 31건(정비 6 · 기타 25 — 본문의 건수) 합 = 요약표 합계
        assertThat(r.sites()).hasSize(31);
        assertThat(r.sites().stream().collect(Collectors.groupingBy(Site::category, Collectors.counting())))
                .isEqualTo(Map.of("정비사업", 6L, "기타사업", 25L));
        assertThat(r.totals().reconciled()).isTrue();
    }

    @Test
    void 대우건설_두_줄_머리와_특수관계자_표는_이중계산하지_않음() {
        var r = parse("daewoo-2026q2");
        assertThat(r.totals().guarantee()).isEqualByComparingTo(m(2_178_528));               // 단독 2,159,727 + 컨소시엄 회사분 18,801
        assertThat(r.sites()).hasSize(24);                                                   // 정비 14 · 기타 10, 특수관계자 표(위 내역의 일부) 제외
        assertThat(r.totals().reconciled()).isTrue();
        Site first = r.sites().getFirst();
        assertThat(first.region()).isEqualTo("서울시");
        assertThat(first.related()).isFalse();                                               // '채무자 | 특수관계자' 두 줄 머리에서 X
        assertThat(first.balance()).isEqualByComparingTo(m(24_730));                         // 대출잔액 당기
        assertThat(first.balancePrior()).isEqualByComparingTo(m(19_590));                    //          전기
        assertThat(first.completionAmount()).isEqualByComparingTo(m(60_000));
    }

    @Test
    void 계룡건설산업_연결_블록만_쓰고_뒤의_회사_별도_블록은_멈춤() {
        var r = parse("kyeryong-2026q2");
        assertThat(r.totals().guarantee()).isEqualByComparingTo(m(62_760 + 692_111));        // 연결실체 (회사 별도 블록 408,274 + 761,951 아님)
        assertThat(r.summary().stream().filter(s -> s.total() && s.scope() == Scope.SOLE)).hasSize(1);
    }

    @Test
    void HL디앤아이_병합된_계_줄은_사업장이_아님() {
        var r = parse("hldni-2026q2");
        assertThat(r.sites()).hasSize(10).noneMatch(s -> s.region().endsWith("계"));
    }

    @Test
    void 서희건설_앞_문단의_SOC_제외_설명과_특수관계자_언급에_세부_표를_버리지_않음() {
        var r = parse("seohee-2026q2");
        assertThat(r.sites()).hasSize(4);
    }

    @Test
    void 아이에스동서_자체사업_주석의_행은_보증이_아니라_합계에서_빼고_전기분으로_한_번_더_실린_세부_표는_건너뜀() {
        var r = parse("isdongseo-2026q2");
        assertThat(r.sites()).hasSize(2);                                                    // 같은 사업장 표가 금액만 바꿔 두 번
        // '(주1) 기타사업은 … 자체 사업 … 직접 PF 차주' — 기타사업(주1) 4,146억은 회사 자신의 차입. 본문 '총 274,112백만원의 신용보강'과 같아짐
        assertThat(r.totals().guarantee()).isEqualByComparingTo(new BigDecimal("274112386000"));
        assertThat(r.totals().reconciled()).isTrue();                                        // v8 까지는 6,887억 vs 세부 2,741억으로 '불일치'
        assertThat(r.message()).contains("자체사업");
    }

    @Test
    void 회사분_표_없이_컨소시엄_전체_표의_보증금액이_회사_몫이면_회사_노출에_더함() {
        // 한신공영 2025.03: <컨소시엄 참여 사업(전체)> 표 머리말 '보증금액(연결) · 대출잔액(전체)' — 본문 '총 436,441 = 단독 346,385 + 컨소시엄 90,056'
        var r = parse("hanshin-2025q1-consortium-all-only");
        assertThat(total(r, Scope.CONSORTIUM_SHARE)).isEqualByComparingTo(m(90_056));
        assertThat(r.totals().guarantee()).isEqualByComparingTo(m(346_385 + 90_056));
    }

    @Test
    void 금호건설_한_표_안의_전체분_당사분_줄은_따로_세고_N이내_만기_구간은_구간_합이_잔액과_같을_때만_인정() {
        var r = parse("kumho-2025q2-row-scope");
        // 원문 ① 종합요약표 '계 346,388' = 단독 188,565 + 컨소시엄(당사분) 157,823 — v11 은 전체분 241,900 까지 더해 588,288
        assertThat(r.totals().guarantee()).isEqualByComparingTo(m(188_565 + 157_823));
        assertThat(total(r, Scope.CONSORTIUM_ALL)).isEqualByComparingTo(m(241_900));
        // '3개월이내 · 6개월이내 · 1년이내 …' — 줄마다 구간 합 = 대출잔액(292,611 · 157,823)이라 구간으로 인정
        assertThat(r.totals().due().within12m()).isEqualByComparingTo(m(31_538 + 122_000 + 126_806));
    }

    @Test
    void KCC건설_단독_컨소시엄_전체_당사분_소계가_한_표에_있어도_소계를_더하지_않음() {
        var r = parse("kcc-2026q2-single-table");
        assertThat(r.totals().guarantee()).isEqualByComparingTo(new BigDecimal("358318742000"));   // 본문 '총 358,318,742천원'
        assertThat(r.statedMatch()).isTrue();
        assertThat(total(r, Scope.CONSORTIUM_SHARE)).isEqualByComparingTo(new BigDecimal("264300000000"));
        assertThat(r.summary()).noneMatch(x -> !x.total() && x.category() != null && x.category().isEmpty());   // '계' 줄이 사업 줄로 섞이지 않음
    }

    @Test
    void 컨소시엄_참여사업은_없다는_문장은_범위_꼬리표가_아님() {
        // HS화성: '단독사업 외 컨소시엄참여사업은 없습니다.' 뒤의 표를 컨소시엄 전체로 보고 합계에서 뺐던 문제
        assertThat(PfExposureParser.scopeOf("…총 61,100백만원의 신용을 보강하고 있으며, 단독사업 외 컨소시엄참여사업은 없습니다. 기타사업과 관련한 PF 우발부채 내역은 아래와 같습니다", 0))
                .isEqualTo(Scope.SOLE);
        // 대우건설 꼬리표: ② 컨소시엄 참여사업(전체분) · ③ 컨소시엄 참여사업(연결회사분)
        assertThat(PfExposureParser.scopeOf("② 컨소시엄 참여사업(전체분)", 1)).isEqualTo(Scope.CONSORTIUM_ALL);
        assertThat(PfExposureParser.scopeOf("③ 컨소시엄 참여사업(연결회사분)", 2)).isEqualTo(Scope.CONSORTIUM_SHARE);
        // 한신공영: 설명 문장(단독 …, 컨소시엄 …) 뒤 '<컨소시엄 참여 사업(전체)>' — 마지막 꼬리표를 따름
        assertThat(PfExposureParser.scopeOf("이중 연결회사 단독 사업에 대한 금액은 346,385백만원 <컨소시엄 참여 사업(전체)>", 0))
                .isEqualTo(Scope.CONSORTIUM_ALL);
    }

    @Test
    void 만기_구간_머리말_표기가_달라도_12개월_내_만기를_빠뜨리지_않음() {
        // 서희건설 '6개월초과1년이내' — v7 은 '6개월초과12개월' 만 찾아 6~12개월(20,000)을 빠뜨려 4,939 로 적게 셈
        assertThat(parse("seohee-2026q2").totals().due().within12m()).isEqualByComparingTo(m(4_939 + 20_000));
        // HL D&I (천원 단위) 총계: 3~6개월 128,300,000천원, 나머지 12개월 안 구간은 '-'
        assertThat(parse("hldni-2026q2").totals().due().within12m()).isEqualByComparingTo(m(128_300));
        // 계룡건설산업 '3월이내 · 3월~6월이내 · 6월~12월이내': 단독은 전부 2~3년(0), 컨소시엄 회사분 113,250 + 33,247
        assertThat(parse("kyeryong-2026q2").totals().due().within12m()).isEqualByComparingTo(m(113_250 + 33_247));
    }

    @Test
    void 만기_구간이_모두_비면_12개월_내_만기는_0이_아니라_알_수_없음() {
        assertThat(new PfExposureParser.Due(null, null, null, null, null, null).within12m()).isNull();
        // 다른 구간에 금액이 있으면 12개월 안의 '-' 는 0
        assertThat(new PfExposureParser.Due(null, null, null, null, m(52_300), null).within12m()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void PF_신용보강이_없고_책임준공_약정_표만_있으면_사업장으로_세지_않고_NONE() {
        var r = parse("camus-2026q2-completion-only");
        assertThat(r.status()).isEqualTo(Status.NONE);
        assertThat(r.sites()).isEmpty();
    }

    @Test
    void 본문_서술_합계를_따로_읽어_표_합계와_대조() {
        var ty = parse("taeyoung-2026q2");
        assertThat(ty.statedTotal()).isEqualByComparingTo(m(2_354_379));
        assertThat(ty.statedMatch()).isTrue();
        assertThat(parse("hanshin-2025q1-consortium-all-only").statedMatch()).isTrue();     // 단독 346,385 + 컨소시엄 90,056
        assertThat(parse("isdongseo-2026q2").statedMatch()).isTrue();                       // 자체사업을 뺀 뒤 274,112
        // GS건설 '보증금액 기준 총 …'
        assertThat(PfExposureParser.statedTotal("연결기업은 PF와 관련하여 보증금액 기준 총 2,938,643백만원(전기말: 3,162,374백만원)의 신용보강을 제공하고"))
                .isEqualByComparingTo(m(2_938_643));
        // 현대건설 'PF(대출)과 관련하여 …'
        assertThat(PfExposureParser.statedTotal("연결회사는 PF(대출)과 관련하여 보증금액 기준 14,184,538백만원(전기말 13,940,533백만원)의 신용보강을 제공"))
                .isEqualByComparingTo(m(14_184_538));
        // 상지건설: 당기 신용보강은 없고 전기말 금액만 — 전기말 금액을 당기 합계로 읽지 않음
        assertThat(PfExposureParser.statedTotal("지배기업은 보고기간말에 본 PF와 관련하여 제공한 신용보강은 없으며, 전기말 55,000백만원의 신용보강을 제공하였습니다.")).isNull();
    }

    @Test
    void 표준_서식_이전_보고서는_추정하지_않고_NO_STANDARD() {
        var r = parse("taeyoung-2022q3-old");
        assertThat(r.status()).isEqualTo(Status.NO_STANDARD);
        assertThat(r.totals()).isNull();
    }

    @Test
    void 단위는_표_앞_문구에서() {
        assertThat(PfExposureParser.unit("(단위: 백만원)")).isEqualByComparingTo(MILLION);
        assertThat(PfExposureParser.unit("(단위 : 천원, 1 USD, 1 EUR)")).isEqualByComparingTo(BigDecimal.valueOf(1_000));
        assertThat(PfExposureParser.unit("(단위:억원)")).isEqualByComparingTo(BigDecimal.valueOf(100_000_000));
    }
}
