package com.buildrisk.radar.domain.account;

import java.math.BigDecimal;
import java.util.Set;

public final class AccountModels {
    private AccountModels() {}

    public enum Basis { POINT, CUM, QTR }

    public record StdAccount(String stdCode, String nameKo, String sjDiv, String flowType, String agg, int sortOrder) {
        public boolean flow() { return "FLOW".equals(flowType); }

        public boolean sum() { return "SUM".equals(agg); }

        /** 규칙에 원천 구분이 없을 때 볼 재무제표 — IS 는 IS·CIS 둘 다 (단일 포괄손익계산서 회사 대비) */
        public Set<String> defaultSources() {
            return "IS".equals(sjDiv) ? Set.of("IS", "CIS") : Set.of(sjDiv);
        }
    }

    /** section: null 이면 어디든, CURRENT·NONCURRENT 면 재무상태표의 해당 부채 구간 행에만 */
    public record MapRule(int mapId, String stdCode, String sjDiv, String matchType, String pattern, int priority,
                          boolean absValue, String section) {
        public MapRule(int mapId, String stdCode, String sjDiv, String matchType, String pattern, int priority, boolean absValue) {
            this(mapId, stdCode, sjDiv, matchType, pattern, priority, absValue, null);
        }
    }

    /** fs_raw 한 행 (표준화에 필요한 열만) */
    public record RawLine(String sjDiv, int lineNo, String accountId, String accountNm, BigDecimal thstrmAmount,
                          BigDecimal thstrmAddAmount) {
        /** 재무상태표는 시점값, 손익은 누적(당기누적 우선), 현금흐름표는 당기금액(보고서 기준 누적) */
        public BigDecimal amountFor() {
            return switch (sjDiv) {
                case "IS", "CIS" -> thstrmAddAmount != null ? thstrmAddAmount : thstrmAmount;
                default -> thstrmAmount;
            };
        }
    }

    /** 한 보고서에서 뽑은 표준계정 값 (BS → POINT, IS·CF → CUM) */
    public record StdValue(String stdCode, Basis basis, BigDecimal amount, String sourceAccount, String sourceSjDiv) {}
}
