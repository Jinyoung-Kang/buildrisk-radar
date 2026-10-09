package com.buildrisk.radar.company.domain;

/** 회사 원천 — DART 어댑터가 만들고 저장소가 씀 */
public final class CompanyModels {
    private CompanyModels() {}

    /** corpCode.xml 한 건 */
    public record CorpCode(String corpCode, String corpName, String corpEngName, String stockCode, String modifyDate) {}

    /** company.json */
    public record CompanyProfile(String corpCode, String corpName, String corpEngName, String stockCode, String corpCls,
                                 String indutyCode, String adres, String accMt, String ceoNm, String hmUrl) {}
}
