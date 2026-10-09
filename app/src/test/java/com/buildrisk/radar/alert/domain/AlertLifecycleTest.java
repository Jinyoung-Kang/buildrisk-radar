package com.buildrisk.radar.alert.domain;

import com.buildrisk.radar.alert.domain.AlertLifecycle.Active;
import com.buildrisk.radar.alert.domain.AlertLifecycle.Close;
import com.buildrisk.radar.alert.domain.AlertLifecycle.Plan;
import com.buildrisk.radar.alert.domain.AlertLifecycle.Upsert;
import com.buildrisk.radar.alert.domain.RuleModels.Evaluation;
import com.buildrisk.radar.alert.domain.RuleModels.Finding;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 경보 상태 전이 (FR-504) — 저장 없이 무엇을 닫고 무엇을 열지 */
class AlertLifecycleTest {
    static Finding f(String asOf) { return new Finding(asOf, "t", "m", Map.of()); }

    static Evaluation ev(String latest, List<String> evaluated, String... fired) {
        return new Evaluation(Arrays.stream(fired).map(AlertLifecycleTest::f).toList(), evaluated, latest, true);
    }

    static List<String> upserts(Plan p) {
        return p.upserts().stream().map(u -> u.finding().asOf() + ":" + (u.open() ? "OPEN" : u.closeReason())).toList();
    }

    @Test
    void 판단할_수_있는_시점이_없으면_기간_규칙은_건드리지_않음() {
        var unknown = new Evaluation(List.of(), List.of("2026Q2"), "2026Q2", false);
        assertThat(AlertLifecycle.decides(true, unknown)).isFalse();
        assertThat(AlertLifecycle.decides(false, unknown)).isTrue();          // 공시 규칙은 건마다
        assertThat(AlertLifecycle.decides(true, ev("2026Q2", List.of("2026Q2")))).isTrue();
    }

    @Test
    void 기간_규칙이_다음_분기로_이어지면_이전_경보는_대체되고_최신만_열림() {
        Plan p = AlertLifecycle.plan(1, true, ev("2026Q3", List.of("2026Q2", "2026Q3"), "2026Q2", "2026Q3"),
                List.of(new Active(10, 1, "2026Q2")));
        assertThat(p.closes()).containsExactly(new Close(10, "SUPERSEDED"));
        assertThat(upserts(p)).containsExactly("2026Q2:SUPERSEDED", "2026Q3:OPEN");
    }

    @Test
    void 기간_규칙의_참인_시점이_모두_사라지면_해소() {
        Plan p = AlertLifecycle.plan(1, true, ev("2026Q3", List.of("2026Q2", "2026Q3")), List.of(new Active(10, 1, "2026Q2")));
        assertThat(p.closes()).containsExactly(new Close(10, "RESOLVED"));
        assertThat(p.upserts()).isEmpty();
    }

    @Test
    void 규칙_버전이_바뀌면_이전_버전_경보는_닫고_새_버전으로_다시_씀() {
        Plan p = AlertLifecycle.plan(2, true, ev("2026Q2", List.of("2026Q2"), "2026Q2"), List.of(new Active(10, 1, "2026Q2")));
        assertThat(p.closes()).containsExactly(new Close(10, "RULE_CHANGED"));
        assertThat(upserts(p)).containsExactly("2026Q2:OPEN");
    }

    @Test
    void 공시_규칙은_건마다_열고_창_안에서_거짓이면_해소_창_밖이면_기간_경과() {
        Plan p = AlertLifecycle.plan(1, false, ev(null, List.of("R1", "R2"), "R1"),
                List.of(new Active(10, 1, "R1"), new Active(11, 1, "R2"), new Active(12, 1, "R0")));
        assertThat(p.closes()).containsExactly(new Close(11, "RESOLVED"), new Close(12, "EXPIRED"));
        assertThat(upserts(p)).containsExactly("R1:OPEN");
    }

    @Test
    void 알림은_새로_생겼거나_닫혔다가_다시_열린_경보만() {
        Upsert open = new Upsert(f("2026Q3"), true, null), closed = new Upsert(f("2026Q2"), false, "SUPERSEDED");
        assertThat(AlertLifecycle.notifyKind(true, closed, true, false, null, false)).isNull();
        assertThat(AlertLifecycle.notifyKind(true, open, true, false, null, false)).isEqualTo("NEW");
        assertThat(AlertLifecycle.notifyKind(true, open, true, true, null, false)).isNull();          // 다음 분기로 이어짐
        assertThat(AlertLifecycle.notifyKind(false, open, true, true, null, false)).isEqualTo("NEW"); // 공시는 건마다
        assertThat(AlertLifecycle.notifyKind(false, open, true, false, null, true)).isNull();         // 새 규칙 버전으로 다시 생김
        assertThat(AlertLifecycle.notifyKind(true, open, false, true, "CLOSED", false)).isEqualTo("REOPENED");
        assertThat(AlertLifecycle.notifyKind(true, open, false, true, "ACK", false)).isNull();
    }
}
