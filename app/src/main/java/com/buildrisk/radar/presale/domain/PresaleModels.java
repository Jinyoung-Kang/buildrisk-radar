package com.buildrisk.radar.presale.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 청약홈 분양 공고 (ADR-032) — 어댑터가 응답을 이 레코드로 풀고, 저장소 · 규칙 · 화면이 같은 모양을 씁니다.
 * raw 는 응답의 한 행(JSON 객체) 그대로 — 바뀌었는지(sha256) 보고 주택형을 다시 받는 기준.
 */
public final class PresaleModels {
    private PresaleModels() {}

    /** 한 쪽 응답: matchCount(조건에 맞는 전체) · 이번 쪽 행 */
    public record Page<T>(int matchCount, List<T> items) {}

    /** 규제 플래그 (원천 Y/N, 그 밖의 값 · 빈 값은 null) */
    public record Flags(Boolean speculativeZone, Boolean adjustedZone, Boolean priceCap, Boolean redevelopment,
                        Boolean publicDistrict, Boolean largeSite, Boolean metroPrivatePublic, Boolean publicHousingAct) {}

    /** APT 분양정보 상세 — 세대 = 공급규모(일반 + 특별공급, 조합원분 제외) */
    public record Notice(String houseManageNo, String pblancNo, String houseNm, String houseSecd, String houseDtlSecd,
                         String rentSecd, String areaCode, String areaName, String zip, String address, Integer totSupplyHh,
                         LocalDate noticeDate, LocalDate receiptBegin, LocalDate receiptEnd, LocalDate specialBegin,
                         LocalDate specialEnd, LocalDate winnerDate, LocalDate contractBegin, LocalDate contractEnd,
                         String builderName, String developerName, String moveinYm, Flags flags, String pblancUrl, String raw) {}

    /** 주택형 — topAmountWon 은 원천 '만원' 문자열 × 10,000 */
    public record Model(String houseManageNo, String pblancNo, String modelNo, String houseTy, BigDecimal supplyArea,
                       Integer supplyHh, Integer specialHh, Map<String, Integer> specialDetail, BigDecimal topAmountWon,
                       String raw) {}

    /** 잔여세대(04 무순위 · 06 불법행위 재공급) 공고 — 시공사 없음 */
    public record Remainder(String houseManageNo, String pblancNo, String houseSecd, String houseNm, String areaName, String zip,
                            String address, Integer totSupplyHh, LocalDate noticeDate, LocalDate receiptBegin,
                            LocalDate receiptEnd, String moveinYm, String developerName, String pblancUrl, String raw) {}

    /** 공고 하나와 그 시공사 대조 결과 — 규칙(R-X02) · 기업 화면이 같은 계산(PresaleRegionShare)에 넣는 값 */
    public record PresaleFact(String houseManageNo, String pblancNo, String houseNm, LocalDate noticeDate, String regionCd,
                              String regionName, String regionMatch, int units, List<String> partners) {}

    /** 수집 데이터셋 (mkt.presale_fetch.dataset) */
    public enum Dataset { APT_NOTICE, REMAINDER }

    /** 이번 실행 뒤 다시 받는 최근 달 수 — 이번 달 포함 3개월 (정정 · 취소 · 늦게 올라온 공고) */
    public static final int REFRESH_MONTHS = 3;

    /** 롤링 '최근 12개월 분양 공고' · '앞으로 12개월 입주 예정' 창 */
    public static final int WINDOW_MONTHS = 12;

    /**
     * '앞으로 12개월 입주 예정'을 낼 때 원장에 있어야 하는 모집공고 달 수(기준월 포함 뒤로). 공고 → 입주예정월 간격의 90% 분위가
     * 43개월(2023-01 ~ 2026-10 공고 1,264건 실측: 중앙값 32 · 90% 43 · 95% 46) — 그보다 짧게 받은 기준월은 앞선 공고가 빠져 값이
     * 작게 나오므로(하한의 하한) 내지 않습니다.
     */
    public static final int MOVEIN_LOOKBACK_MONTHS = 43;

    public static int ym(YearMonth m) { return m.getYear() * 100 + m.getMonthValue(); }

    public static YearMonth parseYm(int ym) { return YearMonth.of(ym / 100, ym % 100); }

    /** 'YYYY-MM' (Job 파라미터 · 설정) → YYYYMM. 형식이 다르면 IllegalArgumentException */
    public static int fromParam(String yyyyMm) {
        if (yyyyMm == null || !yyyyMm.matches("20\\d\\d-(0[1-9]|1[0-2])")) {
            throw new IllegalArgumentException("from 은 YYYY-MM 형식 (2000-01 ~ 2099-12): " + yyyyMm);
        }
        return Integer.parseInt(yyyyMm.substring(0, 4)) * 100 + Integer.parseInt(yyyyMm.substring(5));
    }
}
