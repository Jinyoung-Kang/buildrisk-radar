package com.buildrisk.radar.domain.filing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 공시 원문에서 뽑은 구조 (ADR-016). 원문에 없거나 '-' 인 값은 null. */
public final class FilingModels {
    private FilingModels() {}

    public enum Kind { CONTRACT, TERMINATION, GUARANTEE }

    /** 정정 공시면 정정 대상 원 공시 제출일 · 사유 */
    public record Correction(LocalDate originalDate, String reason) {}

    /** 단일판매ㆍ공급계약 체결 (유가·코스닥 서식 모두) */
    public record Contract(String kind, String name, BigDecimal amount, BigDecimal recentRevenue, BigDecimal pctOfRevenue,
                           String counterparty, String regionText, LocalDate startDate, LocalDate endDate,
                           LocalDate contractDate, Correction correction) {}

    /** 단일판매ㆍ공급계약 해지 */
    public record Termination(String name, BigDecimal amount, String counterparty, String reason, LocalDate terminatedOn,
                              LocalDate originalDate) {}

    /** PF 유형별 보증 (보증 공시 하단 표) */
    public record PfLine(String debtor, String provider, String pfType, BigDecimal amount) {}

    /** 타인에 대한 채무보증 결정 */
    public record Guarantee(String debtor, String debtorRelation, String creditor, BigDecimal borrowing, BigDecimal amount,
                            BigDecimal equity, BigDecimal pctOfEquity, BigDecimal totalBalance, LocalDate startDate,
                            LocalDate endDate, LocalDate decisionDate, List<PfLine> pf, Correction correction,
                            boolean balanceIsLimit, BigDecimal unusedLimit) {
        /** 본문 전체 텍스트에서 주석을 읽어 채움 — 한도 합계가 총 잔액과 ±1% 안에서 맞을 때만 미사용액 */
        public Guarantee withLimitNote(String text) {
            boolean isLimit = FilingParser.limitNote(text);
            BigDecimal unused = FilingParser.unusedLimit(text, totalBalance);
            return new Guarantee(debtor, debtorRelation, creditor, borrowing, amount, equity, pctOfEquity, totalBalance, startDate,
                    endDate, decisionDate, pf, correction, isLimit, unused);
        }

        /** 사용 잔액 = 총 잔액 − 미사용 한도 (주석으로 계산 가능할 때만) */
        public BigDecimal usedBalance() {
            return totalBalance == null || unusedLimit == null ? null : totalBalance.subtract(unusedLimit);
        }

        public BigDecimal pfAmount() {
            return pf.isEmpty() ? null : pf.stream().map(PfLine::amount).filter(a -> a != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }
}
