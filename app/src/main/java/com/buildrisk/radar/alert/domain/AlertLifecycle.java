package com.buildrisk.radar.alert.domain;

import com.buildrisk.radar.alert.domain.RuleModels.Evaluation;
import com.buildrisk.radar.alert.domain.RuleModels.Finding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 경보 상태 전이 (FR-504) — 평가 결과와 지금 열린 경보로 무엇을 닫고 무엇을 열지 정합니다. 저장은 AlertService 가 (ADR-007 멱등키
 * rule_code·rule_version·target_key·as_of).
 *   기간 규칙(singleActive): 대상마다 최신 평가 시점에 참인 경보 하나만 OPEN(ACK 는 유지),
 *                            과거 시점 경보는 SUPERSEDED, 조건이 풀리면 RESOLVED 로 자동 CLOSED
 *   공시 규칙: 건마다 OPEN, 창(windowDays)을 벗어나면 EXPIRED, 창 안인데 재분류로 거짓이 되면 RESOLVED
 *   규칙 버전이 바뀌면 이전 버전의 열린 경보는 RULE_CHANGED
 * 알림 (ADR-025): NEW(그 규칙 · 대상에 열린 경보가 없다가 생김) · REOPENED(닫힌 경보가 다시 참).
 *   기간 규칙이 다음 분기로 이어지기만 한 경우와 규칙 버전이 바뀌어 같은 경보가 새 버전으로 다시 생긴 경우는 알리지 않음(알림 폭주 방지)
 */
public final class AlertLifecycle {
    /** 평가 전에 열려 있던(OPEN · ACK) 경보 */
    public record Active(long alertId, int version, String asOf) {}

    public record Close(long alertId, String reason) {}

    /** 넣거나 고칠 경보 — open 이 아니면 closeReason 으로 닫힌 채 저장 */
    public record Upsert(Finding finding, boolean open, String closeReason) {}

    public record Plan(List<Close> closes, List<Upsert> upserts) {}

    private AlertLifecycle() {}

    /** 판단할 수 있는 시점이 하나도 없으면(값이 없거나 MISSING · ZERO_DENOM …) 열지도 닫지도 않음 — '모름'을 '해소'로 보지 않음 (VERIFICATION 118) */
    public static boolean decides(boolean singleActive, Evaluation ev) {
        return !singleActive || ev.decided();
    }

    public static Plan plan(int ruleVersion, boolean singleActive, Evaluation ev, List<Active> active) {
        Map<String, Finding> fired = new LinkedHashMap<>();
        ev.findings().forEach(f -> fired.put(f.asOf(), f));
        String openAsOf = singleActive && ev.latestAsOf() != null && fired.containsKey(ev.latestAsOf()) ? ev.latestAsOf() : null;

        List<Close> closes = new ArrayList<>();
        for (Active a : active) {
            String reason = null;
            if (a.version() != ruleVersion) reason = "RULE_CHANGED";
            else if (!fired.containsKey(a.asOf())) {
                // 공시 규칙: 창 안에 있는데 참이 아니면(재분류 등) RESOLVED, 창을 벗어났으면 EXPIRED
                reason = singleActive || ev.evaluatedAsOfs().contains(a.asOf()) ? "RESOLVED" : "EXPIRED";
            }
            else if (singleActive && !a.asOf().equals(openAsOf)) reason = "SUPERSEDED";
            if (reason != null) closes.add(new Close(a.alertId(), reason));
        }

        List<Upsert> upserts = new ArrayList<>();
        for (Finding f : fired.values()) {
            boolean open = !singleActive || f.asOf().equals(openAsOf);
            upserts.add(new Upsert(f, open, open ? null : openAsOf != null ? "SUPERSEDED" : "RESOLVED"));
        }
        return new Plan(closes, upserts);
    }

    /**
     * 저장한 경보를 알릴지 — NEW · REOPENED, 알리지 않으면 null.
     * inserted: 이번에 새로 생긴 행 · hadActive: 평가 전 그 규칙 · 대상에 열린 경보가 있었음 ·
     * prevStatus: 같은 버전 · 시점 경보의 평가 전 상태(없으면 null) · otherVersion: 같은 시점에 다른 버전 경보가 있었음
     */
    public static String notifyKind(boolean singleActive, Upsert u, boolean inserted, boolean hadActive, String prevStatus,
                                    boolean otherVersion) {
        if (!u.open()) return null;
        if (inserted) return singleActive ? (hadActive ? null : "NEW") : (otherVersion ? null : "NEW");
        return "CLOSED".equals(prevStatus) ? "REOPENED" : null;
    }
}
