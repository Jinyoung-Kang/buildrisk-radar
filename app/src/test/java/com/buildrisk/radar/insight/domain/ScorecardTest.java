package com.buildrisk.radar.insight.domain;

import com.buildrisk.radar.insight.domain.Scorecard.CreditEvent;
import com.buildrisk.radar.insight.domain.Scorecard.Outcome;
import com.buildrisk.radar.insight.domain.Scorecard.Signal;
import com.buildrisk.radar.insight.domain.Scorecard;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScorecardTest {
    static final LocalDate FROM = LocalDate.of(2021, 5, 15), TO = LocalDate.of(2026, 9, 30);

    static Signal s(String rule, String corp, String date) { return new Signal(rule, corp, "p", LocalDate.parse(date)); }

    static CreditEvent ev(String corp, String date) { return new CreditEvent(corp, LocalDate.parse(date), "회생", "x", "x"); }

    @Test
    void 분기마다_반복된_신호는_한_에피소드이고_사건_전_H개월_안이면_적중() {
        // A: 2024-05 · 08 · 11 연속 신호 → 2025-02 회생 (첫 신호에서 9개월)
        var sig = List.of(s("R", "A", "2024-05-15"), s("R", "A", "2024-08-14"), s("R", "A", "2024-11-14"));
        var r = Scorecard.score(List.of("R"), sig, List.of("A", "B"), Map.of("A", ev("A", "2025-02-24")), FROM, TO, 12);
        assertThat(r.episodes()).singleElement().satisfies(e -> {
            assertThat(e.signals()).isEqualTo(3);
            assertThat(e.outcome()).isEqualTo(Outcome.HIT);
            assertThat(e.leadDays()).isEqualTo(285);
        });
        var c = r.catches().getFirst();
        assertThat(c.caught()).isTrue();
        assertThat(c.firstSignal()).isEqualTo(LocalDate.parse("2024-05-15"));   // 창 안 신호가 속한 에피소드의 시작
        assertThat(r.rules().getFirst().recall()).isEqualTo(1.0);
    }

    @Test
    void H개월이_지나도_사건이_없으면_빗나감_아직_안_지났으면_보류_사건_뒤_신호는_정밀도에서_뺌() {
        var sig = List.of(
                s("R", "B", "2022-05-16"),                          // B: 사건 없음, 12개월 지남 → MISS
                s("R", "C", "2026-05-15"),                          // C: 12개월 안 지남 → PENDING
                s("R", "A", "2025-05-15"));                         // A: 사건(2025-02) 뒤 → AFTER_EVENT
        var r = Scorecard.score(List.of("R"), sig, List.of("A", "B", "C"), Map.of("A", ev("A", "2025-02-24")), FROM, TO, 12);
        assertThat(r.episodes()).extracting(Scorecard.Episode::outcome)
                .containsExactlyInAnyOrder(Outcome.MISS, Outcome.PENDING, Outcome.AFTER_EVENT);
        var score = r.rules().getFirst();
        assertThat(score.hits()).isZero();
        assertThat(score.misses()).isEqualTo(1);
        assertThat(score.precision()).isEqualTo(0.0);
        assertThat(score.recall()).isEqualTo(0.0);                 // A 의 사건 전에는 신호가 없었음
    }

    @Test
    void 오래_켜져_있던_경보도_마지막_신호_뒤_H개월_안에_사건이면_적중이고_선행은_시작부터() {
        // 2023-05 부터 분기마다 이어진 신호 → 2025-02 회생 (시작에서 21개월, 마지막 신호에서 3개월)
        var sig = List.of(s("R", "A", "2023-05-15"), s("R", "A", "2023-08-14"), s("R", "A", "2023-11-14"), s("R", "A", "2024-03-20"),
                s("R", "A", "2024-05-15"), s("R", "A", "2024-08-14"), s("R", "A", "2024-11-14"));
        var r = Scorecard.score(List.of("R"), sig, List.of("A"), Map.of("A", ev("A", "2025-02-24")), FROM, TO, 12);
        assertThat(r.episodes()).singleElement().satisfies(e -> {
            assertThat(e.outcome()).isEqualTo(Outcome.HIT);
            assertThat(e.leadDays()).isEqualTo(651);
        });
        assertThat(r.catches().getFirst().leadDays()).isEqualTo(651);
    }

    @Test
    void 신호가_200일_넘게_끊기면_새_에피소드() {
        var sig = List.of(s("R", "B", "2022-05-16"), s("R", "B", "2023-05-15"));
        var r = Scorecard.score(List.of("R"), sig, List.of("B"), Map.of(), FROM, TO, 12);
        assertThat(r.episodes()).hasSize(2);
    }

    @Test
    void ANY_는_규칙을_합쳐_하나의_에피소드로() {
        var sig = List.of(s("R1", "A", "2024-05-15"), s("R2", "A", "2024-08-14"));
        var r = Scorecard.score(List.of("R1", "R2", Scorecard.ANY), sig, List.of("A"), Map.of("A", ev("A", "2025-02-24")), FROM, TO, 12);
        assertThat(r.rules()).extracting(Scorecard.RuleScore::ruleCode).containsExactly("R1", "R2", Scorecard.ANY);
        assertThat(r.episodes().stream().filter(e -> e.ruleCode().equals(Scorecard.ANY))).singleElement()
                .satisfies(e -> assertThat(e.signals()).isEqualTo(2));
    }

    @Test
    void 관측_시작_직후의_사건은_평가_불가() {
        var r = Scorecard.score(List.of("R"), List.of(), List.of("A"), Map.of("A", ev("A", "2021-06-01")), FROM, TO, 12);
        assertThat(r.catches().getFirst().evaluable()).isFalse();
        assertThat(r.rules().getFirst().recall()).isNull();
    }

    @Test
    void 규칙별_관측_시작보다_이른_사건은_그_규칙으로는_평가_불가() {
        // PF 지표는 2024-03 부터 있음 → 2023-12 사건은 PF 규칙으로 평가하지 않고, 재무 규칙(2021 부터)으로는 평가
        var events = Map.of("A", ev("A", "2023-12-28"), "B", ev("B", "2025-04-01"));
        var r = Scorecard.score(List.of("R", "PF"), List.of(s("PF", "B", "2024-08-14")), List.of("A", "B"), events,
                FROM, Map.of("PF", LocalDate.of(2024, 3, 15)), TO, 12);
        var pf = r.rules().get(1);
        assertThat(pf.observedFrom()).isEqualTo(LocalDate.of(2024, 3, 15));
        assertThat(pf.events()).isEqualTo(1);                    // B 만
        assertThat(pf.caught()).isEqualTo(1);
        assertThat(r.rules().getFirst().events()).isEqualTo(2);  // R 은 둘 다 평가
        assertThat(r.rules().getFirst().observedFrom()).isEqualTo(FROM);
    }

    @Test
    void 기준선은_아무_기업_아무_분기말에서_H개월_안_사건_비율() {
        // 기업 2곳, 2024-01-01 ~ 2025-12-31: 표본 시점 2024-01 · 04 · 07 · 10 (t+12개월 ≤ to) × 2
        // A 는 2024-09 사건 → 사건 전 3개 표본 모두 적중, 10월 표본은 이미 부실이라 제외 → 4 + 3 = 7, 적중 3
        var r = Scorecard.score(List.of("R"), List.of(), List.of("A", "B"), Map.of("A", ev("A", "2024-09-01")),
                LocalDate.of(2024, 1, 1), LocalDate.of(2025, 12, 31), 12);
        assertThat(r.baseSamples()).isEqualTo(4 + 3);
        assertThat(r.baseRate()).isCloseTo(3.0 / 7, within(1e-9));
    }

    @Test
    void Wilson_구간은_표본이_작을수록_넓다() {
        var sig = List.of(s("R", "A", "2024-05-15"));
        var r = Scorecard.score(List.of("R"), sig, List.of("A"), Map.of("A", ev("A", "2025-02-24")), FROM, TO, 12);
        var ci = r.rules().getFirst().precisionCi();
        assertThat(ci.low()).isCloseTo(0.2065, within(1e-3));    // 1/1 의 Wilson 95% 하한
        assertThat(ci.high()).isEqualTo(1.0);
    }
}
