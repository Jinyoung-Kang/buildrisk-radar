package com.buildrisk.radar.market.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 시장 원천 — 아파트 실거래 · 주식 일별 시세 */
public final class MarketModels {
    private MarketModels() {}

    /** 아파트 매매 실거래 한 건 (국토교통부 RTMS) */
    public record Trade(LocalDate dealDate, String umdNm, String aptNm, String jibun, BigDecimal excluUseAr, Integer floor,
                        Integer buildYear, long dealAmount, String dealingGbn, boolean cancelled, LocalDate cancelDate,
                        String buyerGbn, String sellerGbn) {}

    /** 주식 일별 시세 한 건 (금융위원회) */
    public record Daily(LocalDate basDt, String srtnCd, BigDecimal clpr, BigDecimal mkp, BigDecimal hipr, BigDecimal lopr,
                        Long trqu, BigDecimal fltRt, Long lstgStCnt, BigDecimal mrktTotAmt) {}

    /** 사건 연구 · 노출 지표에 쓰는 일별 종가 (mkt.stock_daily 한 행) */
    public record Bar(String stockCode, LocalDate basDt, BigDecimal clpr, Long lstgStCnt, BigDecimal mrktTotAmt) {}
}
