package com.buildrisk.radar.company.domain;

import com.buildrisk.radar.company.domain.AccountModels.RawLine;

import java.math.BigDecimal;
import java.util.List;

/** 재무제표 수집 · 표준화 단위 (fs_raw → fs_std) */
public final class FsModels {
    private FsModels() {}

    /** (기업 × 연도 × 보고서) 한 조합 */
    public record FsTarget(String corpCode, String bsnsYear, String reprtCode) {
        public String key() { return corpCode + ":" + bsnsYear + ":" + reprtCode; }
    }

    /** fnlttSinglAcntAll.json list 한 행 */
    public record FsRow(int lineNo, String rceptNo, String sjDiv, Integer ord, String accountId, String accountNm,
                        String accountDetail, BigDecimal thstrmAmount, BigDecimal thstrmAddAmount,
                        BigDecimal frmtrmAmount, String currency) {}

    /** 한 보고서(최신 접수번호)의 원천 행 묶음 */
    public record Report(String corpCode, String bsnsYear, String reprtCode, String fsDiv, String rceptNo, List<RawLine> lines) {
        public String periodKey() { return PeriodKeys.key(bsnsYear, reprtCode); }
    }

    public record StdRow(String corpCode, String periodKey, String fsDiv, String stdCode, String basis, BigDecimal amount,
                         String sourceAccount, String sourceSjDiv, String rceptNo, String reprtCode) {}
}
