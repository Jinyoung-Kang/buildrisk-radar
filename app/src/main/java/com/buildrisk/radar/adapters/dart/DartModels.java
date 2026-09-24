package com.buildrisk.radar.adapters.dart;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class DartModels {
    private DartModels() {}

    /** corpCode.xml 한 건 */
    public record CorpCode(String corpCode, String corpName, String corpEngName, String stockCode, String modifyDate) {}

    /** company.json */
    public record CompanyProfile(String corpCode, String corpName, String corpEngName, String stockCode, String corpCls,
                                 String indutyCode, String adres, String accMt, String ceoNm, String hmUrl) {}

    /** fnlttSinglAcntAll.json list 한 행 */
    public record FsRow(int lineNo, String rceptNo, String sjDiv, Integer ord, String accountId, String accountNm,
                        String accountDetail, BigDecimal thstrmAmount, BigDecimal thstrmAddAmount,
                        BigDecimal frmtrmAmount, String currency) {}

    /** 재무제표 응답 — noData 면 rows 는 비어 있음 (013) */
    public record FsResponse(String fsDiv, boolean noData, List<FsRow> rows) {}

    /** list.json 한 건 */
    public record Disclosure(String rceptNo, String corpCode, String corpName, String reportNm, LocalDate rceptDt,
                             String flrNm, String rm) {}

    public record DisclosurePage(int pageNo, int totalPage, int totalCount, List<Disclosure> items) {}
}
