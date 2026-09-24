package com.buildrisk.radar.domain.metric;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;

/** 지역 지표 (7-2). 입력은 화면 단위 시군구로 이미 모인 월별 시계열. */
public final class RegionMetricCalculator {
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    /** unsold/sale/jeonse: YYYYMM → 값, households: YYYY → 값 */
    public record Series(NavigableMap<String, BigDecimal> unsold, NavigableMap<String, BigDecimal> sale,
                         NavigableMap<String, BigDecimal> jeonse, NavigableMap<String, BigDecimal> households) {}

    private RegionMetricCalculator() {}

    public static List<MetricValue> compute(String regionCd, Series s) {
        TreeSet<String> periods = new TreeSet<>();
        periods.addAll(s.unsold().keySet());
        periods.addAll(s.sale().keySet());
        List<MetricValue> out = new ArrayList<>();
        for (String p : periods) {
            String p3 = shift(p, -3);
            BigDecimal u = s.unsold().get(p);
            BigDecimal u3 = s.unsold().get(p3);
            if (s.unsold().containsKey(p)) {
                out.add(of(regionCd, p, "UNSOLD_UNITS", u, u == null ? "MISSING" : MetricValue.OK,
                        Map.of("unsold", nz(u), "source", "KOSIS DT_MLTM_2082")));
                var hh = s.households().floorEntry(p.substring(0, 4));
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("unsold", nz(u));
                c.put("households", hh == null ? "-" : hh.getValue());
                c.put("householdsYear", hh == null ? "-" : hh.getKey());
                if (u == null || hh == null || hh.getValue() == null) {
                    out.add(of(regionCd, p, "UNSOLD_PER_1K_HH", null, "MISSING", c));
                } else if (hh.getValue().signum() == 0) {
                    out.add(of(regionCd, p, "UNSOLD_PER_1K_HH", null, "ZERO_DENOM", c));
                } else {
                    out.add(of(regionCd, p, "UNSOLD_PER_1K_HH",
                            u.multiply(BigDecimal.valueOf(1000)).divide(hh.getValue(), MC), MetricValue.OK, c));
                }
                Map<String, Object> c3 = Map.of("unsold", nz(u), "unsold3mAgo", nz(u3), "period3mAgo", p3);
                if (u == null || u3 == null) {
                    out.add(of(regionCd, p, "UNSOLD_3M_CHG", null, "MISSING", c3));
                } else if (u3.signum() == 0) {
                    // 0 → 0 은 변화 없음, 0 → 양수는 증감률 정의 불가
                    out.add(u.signum() == 0 ? of(regionCd, p, "UNSOLD_3M_CHG", BigDecimal.ZERO, MetricValue.OK, c3)
                            : of(regionCd, p, "UNSOLD_3M_CHG", null, "ZERO_DENOM", c3));
                } else {
                    out.add(of(regionCd, p, "UNSOLD_3M_CHG",
                            u.subtract(u3).divide(u3, MC).multiply(BigDecimal.valueOf(100)), MetricValue.OK, c3));
                }
            }
            BigDecimal sp = s.sale().get(p), sp3 = s.sale().get(p3);
            BigDecimal jp = s.jeonse().get(p), jp3 = s.jeonse().get(p3);
            BigDecimal dSale = sp != null && sp3 != null ? sp.subtract(sp3) : null;
            BigDecimal dJeonse = jp != null && jp3 != null ? jp.subtract(jp3) : null;
            if (s.sale().containsKey(p)) {
                out.add(of(regionCd, p, "PRICE_IDX_3M_CHG", dSale, dSale == null ? "MISSING" : MetricValue.OK,
                        Map.of("saleIdx", nz(sp), "saleIdx3mAgo", nz(sp3), "period3mAgo", p3,
                                "source", "R-ONE A_2024_00045")));
            }
            if (s.jeonse().containsKey(p)) {
                out.add(of(regionCd, p, "JEONSE_IDX_3M_CHG", dJeonse, dJeonse == null ? "MISSING" : MetricValue.OK,
                        Map.of("jeonseIdx", nz(jp), "jeonseIdx3mAgo", nz(jp3), "period3mAgo", p3,
                                "source", "R-ONE A_2024_00050")));
                BigDecimal gap = dSale != null && dJeonse != null ? dJeonse.subtract(dSale) : null;
                out.add(of(regionCd, p, "JEONSE_SALE_GAP", gap, gap == null ? "MISSING" : MetricValue.OK,
                        Map.of("saleChg3m", nz(dSale), "jeonseChg3m", nz(dJeonse))));
            }
        }
        return out;
    }

    public static String shift(String yyyymm, int months) {
        return YearMonth.parse(yyyymm, YM).plusMonths(months).format(YM);
    }

    private static MetricValue of(String r, String p, String code, BigDecimal v, String status, Map<String, Object> c) {
        return new MetricValue(r, p, code, v == null ? null : CompanyMetricCalculator.scale(v), status, c);
    }

    private static Object nz(Object o) { return o == null ? "-" : o; }

    public static NavigableMap<String, BigDecimal> map() { return new TreeMap<>(); }
}
