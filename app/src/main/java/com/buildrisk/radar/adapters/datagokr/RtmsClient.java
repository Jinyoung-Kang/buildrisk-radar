package com.buildrisk.radar.adapters.datagokr;

import com.buildrisk.radar.adapters.common.UpstreamException;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 국토교통부 아파트 매매 실거래가 (RTMSDataSvcAptTrade). 한 요청 = 시군구(LAWD_CD, 법정동 5자리 = V-World 시군구 코드) × 계약월.
 * 계약월 기준 자료라 신고가 늦게 들어오는 최근 달은 수집할 때마다 건수가 늘고, 해제(cdealType=O)도 나중에 표시됩니다
 * (실측: 2026-09-25 강남구 7월 167건 · 6월 228건 — docs/VERIFICATION.md 재수집 비교).
 */
@Component
public class RtmsClient {
    public static final String PROVIDER = "RTMS";
    private static final String PATH = "/1613000/RTMSDataSvcAptTrade/getRTMSDataSvcAptTrade";
    private static final int PAGE = 1000;
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter CDEAL = DateTimeFormatter.ofPattern("yy.MM.dd");
    private static final XMLInputFactory XML = secureFactory();

    public record Trade(LocalDate dealDate, String umdNm, String aptNm, String jibun, BigDecimal excluUseAr, Integer floor,
                        Integer buildYear, long dealAmount, String dealingGbn, boolean cancelled, LocalDate cancelDate,
                        String buyerGbn, String sellerGbn) {}

    record Page(int totalCount, List<Trade> items) {}

    private final DataGoKrGateway gateway;

    public RtmsClient(DataGoKrGateway gateway) { this.gateway = gateway; }

    /** 한 시군구 · 한 달의 전체 거래 (1,000건씩 페이지) */
    public List<Trade> trades(String lawdCd, YearMonth ym) {
        List<Trade> out = new ArrayList<>();
        int page = 1;
        Page p;
        do {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("LAWD_CD", lawdCd);
            q.put("DEAL_YMD", ym.format(YM));
            q.put("pageNo", page);
            q.put("numOfRows", PAGE);
            p = parse(gateway.get(PROVIDER, "getRTMSDataSvcAptTrade", PATH, q));
            out.addAll(p.items());
            page++;
        } while (!p.items().isEmpty() && out.size() < p.totalCount());
        return out;
    }

    static Page parse(String xml) {
        try {
            XMLStreamReader r = XML.createXMLStreamReader(new StringReader(xml));
            List<Trade> items = new ArrayList<>();
            Map<String, String> cur = null;
            String resultCode = null, resultMsg = null;
            int total = 0;
            String tag = null;
            StringBuilder text = new StringBuilder();
            while (r.hasNext()) {
                switch (r.next()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        tag = r.getLocalName();
                        text.setLength(0);
                        if ("item".equals(tag)) cur = new HashMap<>();
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> text.append(r.getText());
                    case XMLStreamConstants.END_ELEMENT -> {
                        String name = r.getLocalName();
                        String v = text.toString().trim();
                        if ("item".equals(name)) {
                            items.add(toTrade(cur));
                            cur = null;
                        } else if (cur != null) {
                            cur.put(name, v);
                        } else if ("resultCode".equals(name)) {
                            resultCode = v;
                        } else if ("resultMsg".equals(name)) {
                            resultMsg = v;
                        } else if ("totalCount".equals(name)) {
                            total = v.isEmpty() ? 0 : Integer.parseInt(v);
                        }
                        text.setLength(0);
                    }
                    default -> { }
                }
            }
            if (resultCode != null && !resultCode.equals("000") && !resultCode.equals("00")) {
                throw new UpstreamException(PROVIDER, resultCode + " " + resultMsg);
            }
            return new Page(total, items);
        } catch (XMLStreamException e) {
            throw new UpstreamException(PROVIDER, "XML 파싱 실패: " + e.getMessage(), e);
        }
    }

    private static Trade toTrade(Map<String, String> m) {
        int y = Integer.parseInt(m.get("dealYear")), mo = Integer.parseInt(m.get("dealMonth")), d = Integer.parseInt(m.get("dealDay"));
        String cancelDay = blankToNull(m.get("cdealDay"));
        LocalDate cancelDate = null;
        if (cancelDay != null) {
            try {
                cancelDate = LocalDate.parse(cancelDay, CDEAL);
            } catch (RuntimeException ignored) {
                // 형식이 다르면 날짜만 비움 (해제 여부는 cdealType 으로 판단)
            }
        }
        return new Trade(LocalDate.of(y, mo, d), blankToNull(m.get("umdNm")), blankToNull(m.get("aptNm")),
                blankToNull(m.get("jibun")), new BigDecimal(m.get("excluUseAr")), intOrNull(m.get("floor")),
                intOrNull(m.get("buildYear")), Long.parseLong(m.get("dealAmount").replace(",", "").trim()),
                blankToNull(m.get("dealingGbn")), "O".equals(blankToNull(m.get("cdealType"))), cancelDate,
                blankToNull(m.get("buyerGbn")), blankToNull(m.get("slerGbn")));
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static Integer intOrNull(String s) {
        String v = blankToNull(s);
        return v == null ? null : Integer.valueOf(v);
    }

    /** XXE 방지 — DTD · 외부 엔티티 끔 */
    private static XMLInputFactory secureFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f;
    }
}
