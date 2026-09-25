package com.buildrisk.radar.domain.filing;

import com.buildrisk.radar.domain.filing.FilingModels.Contract;
import com.buildrisk.radar.domain.filing.FilingModels.Correction;
import com.buildrisk.radar.domain.filing.FilingModels.Guarantee;
import com.buildrisk.radar.domain.filing.FilingModels.PfLine;
import com.buildrisk.radar.domain.filing.FilingModels.Termination;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DART 공시 원문(XForms HTML) → 구조 (ADR-016).
 * 서식은 '항목명 칸 + 값 칸(class=xforms_input)' 표이고, 항목 묶음은 rowspan 으로 표현됩니다. 유가·코스닥 서식과
 * 정정 공시의 항목 번호·표기가 조금씩 달라(계약금액(원) ↔ 계약금액 총액(원), 계약상대 ↔ 계약상대방) 번호·공백·가운뎃점을
 * 뗀 '항목 경로'(예: 계약내역>최근매출액(원))로 찾습니다. 정정 공시 맨 위 '정정사항' 표는 항목명 칸이 없어 자연히 건너뜁니다.
 * 원문은 DB 에 그대로 두고 파서 버전을 올리면 호출 없이 다시 파싱합니다.
 */
public final class FilingParser {
    /**
     * 2: 자율공시 서식(세부내용 · 세부물건 · 계약(수주)일) · 해지의 관련공시에서 '공급계약 체결' 날짜를 골라 읽음
     * 3: 채무보증 주석 — '총 잔액은 한도이며 미사용잔액 포함' 표기 · 한도별 미사용액
     */
    public static final int VERSION = 3;
    private static final Pattern LIMIT_NOTE = Pattern.compile("총\\s*잔액[^.。]{0,40}한도[^.。]{0,20}미사용");
    private static final Pattern LIMIT_LINE = Pattern.compile(
            "보증\\s*한도\\s*(?:는|은)?\\s*[:：]?\\s*([\\d,]+)\\s*(백만원|억원|억)\\s*\\(\\s*미사용\\s*한도\\s*([\\d,]+)\\s*(백만원|억원|억)");
    private static final Pattern NUMBERING = Pattern.compile("^(\\d+\\.|[-※·ㆍ]+)\\s*");
    private static final Pattern DATE = Pattern.compile("(\\d{4})[-.년/ ]\\s*(\\d{1,2})[-.월/ ]\\s*(\\d{1,2})");

    private FilingParser() {}

    /** 항목 경로 → 값. 같은 경로가 여러 번 나오면 처음 값 (정정 공시 본문이 뒤에 있어도 정정표는 경로가 없음) */
    public static Map<String, String> fields(String html) {
        Document doc = Jsoup.parse(html);
        Map<String, String> out = new LinkedHashMap<>();
        List<String[]> spans = new ArrayList<>();   // [label, remainingRows]
        String section = null;                       // 마지막 번호 항목 ('- 회사와의 관계' 같은 하위 항목의 부모)
        for (Element tr : doc.select("tr")) {
            List<String> prefix = new ArrayList<>();
            for (String[] s : spans) prefix.add(s[0]);
            List<String> labels = new ArrayList<>();
            List<String> values = new ArrayList<>();
            List<String[]> pending = new ArrayList<>();
            for (Element td : tr.select("> td, > th")) {
                boolean input = !td.select(".xforms_input").isEmpty();
                String text = clean(td.text());
                if (input) {
                    values.add(text);
                } else if (!text.isEmpty()) {
                    labels.add(text);
                    int rs = rowspan(td);
                    if (rs > 1) pending.add(new String[]{text, String.valueOf(rs - 1)});
                }
            }
            // 이미 지나간 rowspan 은 한 줄 줄이고, 이 줄에서 시작한 rowspan 을 다음 줄부터 적용
            for (Iterator<String[]> it = spans.iterator(); it.hasNext(); ) {
                String[] s = it.next();
                int left = Integer.parseInt(s[1]) - 1;
                if (left <= 0) it.remove(); else s[1] = String.valueOf(left);
            }
            spans.addAll(pending);
            if (labels.isEmpty()) continue;
            String first = labels.get(0);
            if (first.matches("^\\d+\\..*")) section = key(first);
            List<String> path = new ArrayList<>();
            prefix.forEach(p -> path.add(key(p)));
            if (prefix.isEmpty() && first.startsWith("-") && section != null) path.add(section);
            labels.forEach(l -> path.add(key(l)));
            if (values.size() == 1) {
                out.putIfAbsent(String.join(">", path), values.get(0));
            } else if (values.isEmpty() && labels.size() == 2) {
                out.putIfAbsent(key(first), labels.get(1));          // '관련공시 | 2024-05-31 …' 처럼 값 칸 서식이 없는 줄
            } else if (values.isEmpty()) {
                out.putIfAbsent(String.join(">", path), "");
            }
        }
        return out;
    }

    public static Contract contract(String html) {
        Map<String, String> f = fields(html);
        String name = first(f, "판매공급계약구분>체결계약명", "체결계약명", "판매공급계약내용",
                "판매공급계약구분>세부내용", "세부내용");                  // 자율공시 서식
        return new Contract(first(f, "판매공급계약구분"), name,
                amount(first(f, "계약내역>계약금액총액(원)", "계약내역>계약금액(원)", "계약내역>확정계약금액")),
                amount(first(f, "계약내역>최근매출액(원)")),
                amount(first(f, "계약내역>매출액대비(%)")),
                first(f, "계약상대방", "계약상대"),
                first(f, "판매공급지역"),
                date(first(f, "계약기간>시작일")), date(first(f, "계약기간>종료일")),
                date(first(f, "계약(수주)일자", "계약(수주)일")),
                correction(f));
    }

    public static Termination termination(String html) {
        Map<String, String> f = fields(html);
        return new Termination(first(f, "판매공급계약해지구분>해지계약명", "해지계약명",
                        "판매공급계약해지구분>세부물건", "세부물건", "판매공급계약해지구분>세부내용"),
                amount(first(f, "해지내역>해지금액(원)")), first(f, "계약상대방", "계약상대"),
                first(f, "해지주요사유"), date(first(f, "해지일자")), date(related(f)));
    }

    public static Guarantee guarantee(String html) {
        Map<String, String> f = fields(html);
        return new Guarantee(first(f, "채무자"), first(f, "채무자>회사와의관계"), first(f, "채권자"),
                amount(first(f, "채무(차입)금액(원)")),
                amount(first(f, "채무보증내역>채무보증금액(원)")),
                amount(first(f, "채무보증내역>자기자본(원)")),
                amount(first(f, "채무보증내역>자기자본대비(%)")),
                amount(first(f, "채무보증총잔액(원)")),
                date(first(f, "채무보증내역>채무보증기간>시작일", "채무보증기간>시작일")),
                date(first(f, "채무보증내역>채무보증기간>종료일", "채무보증기간>종료일", "종료일")),
                date(first(f, "이사회결의일(결정일)")),
                pfLines(html), correction(f), false, null).withLimitNote(Jsoup.parse(html).text());
    }

    public static boolean limitNote(String text) { return text != null && LIMIT_NOTE.matcher(text).find(); }

    /** '… 보증한도 : 300,967백만원(미사용한도 76,883백만원) …' 들의 미사용 합 — 한도 합이 총 잔액과 ±1% 로 맞을 때만 */
    public static BigDecimal unusedLimit(String text, BigDecimal totalBalance) {
        if (text == null || totalBalance == null || totalBalance.signum() <= 0) return null;
        Matcher m = LIMIT_LINE.matcher(text);
        BigDecimal limits = BigDecimal.ZERO, unused = BigDecimal.ZERO;
        int n = 0;
        while (m.find()) {
            limits = limits.add(won(m.group(1), m.group(2)));
            unused = unused.add(won(m.group(3), m.group(4)));
            n++;
        }
        if (n == 0) return null;
        BigDecimal diff = limits.subtract(totalBalance).abs();
        return diff.compareTo(totalBalance.movePointLeft(2)) <= 0 ? unused : null;
    }

    private static BigDecimal won(String num, String unit) {
        BigDecimal v = new BigDecimal(num.replace(",", ""));
        return unit.startsWith("억") ? v.movePointRight(8) : v.movePointRight(6);
    }

    /** 'PF 유형' 머리글이 있는 표: 채무자 · 보증제공처 · PF 유형 · 유형별 금액 (합계 줄 제외) */
    static List<PfLine> pfLines(String html) {
        List<PfLine> out = new ArrayList<>();
        for (Element table : Jsoup.parse(html).select("table")) {
            List<Element> rows = table.select("tr");
            if (rows.isEmpty()) continue;
            List<String> head = rows.get(0).select("> td, > th").stream().map(e -> key(clean(e.text()))).toList();
            int type = head.indexOf("PF유형");
            if (type < 0) continue;
            int debtor = head.indexOf("채무자"), provider = Math.max(head.indexOf("보증제공처(금융기관등)"), head.indexOf("보증제공처"));
            int amount = head.indexOf("유형별금액");
            for (Element tr : rows.subList(1, rows.size())) {
                List<String> c = tr.select("> td, > th").stream().map(e -> clean(e.text())).toList();
                if (c.isEmpty() || c.get(0).startsWith("합계") || c.size() <= Math.max(type, amount)) continue;
                out.add(new PfLine(get(c, debtor), get(c, provider), get(c, type), amount(get(c, amount))));
            }
        }
        return out;
    }

    private static Correction correction(Map<String, String> f) {
        LocalDate orig = date(first(f, "정정관련공시서류제출일"));
        return orig == null ? null : new Correction(orig, first(f, "정정사유"));
    }

    private static final Pattern CONTRACT_REF = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\s*단일판매\\s*[ㆍ·]?\\s*공급계약\\s*체결");

    /**
     * '관련공시 | 2023-02-09 투자판단… 2024-06-28 단일판매ㆍ공급계약 체결(자율공시)' — 해지 대상 원 공시일.
     * 여러 건이면 '공급계약 체결' 이 붙은 마지막 날짜, 없으면 첫 날짜
     */
    private static String related(Map<String, String> f) {
        String v = first(f, "관련공시");
        if (v == null) return null;
        Matcher m = CONTRACT_REF.matcher(v);
        String hit = null;
        while (m.find()) hit = m.group(1);
        return hit != null ? hit : v.split("\\s+")[0];
    }

    // ------------------------------------------------------------ 값 정리

    /** 항목명 → 경로 키: 번호·기호·공백·가운뎃점 제거 */
    static String key(String label) {
        String s = label;
        Matcher m;
        while ((m = NUMBERING.matcher(s)).find()) s = s.substring(m.end());
        return s.replaceAll("[\\sㆍ·]", "");
    }

    private static String clean(String s) {
        String t = Jsoup.clean(s, Safelist.none()).replace("&nbsp;", " ").replace(' ', ' ').trim();
        return t.replaceAll("\\s+", " ");
    }

    private static int rowspan(Element td) {
        try {
            return td.hasAttr("rowspan") ? Integer.parseInt(td.attr("rowspan").trim()) : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String first(Map<String, String> f, String... keys) {
        for (String k : keys) {
            String v = f.get(k);
            if (v != null && !v.isBlank() && !v.equals("-")) return v;
        }
        return null;
    }

    private static String get(List<String> c, int i) {
        if (i < 0 || i >= c.size()) return null;
        String v = c.get(i);
        return v == null || v.isBlank() || v.equals("-") ? null : v;
    }

    /** '882,824,000,000' · '2.84' · '(1,234)' · '30,367,200,000원' → 숫자, 아니면 null */
    static BigDecimal amount(String s) {
        if (s == null) return null;
        String t = s.replace(",", "").replace("원", "").replace("%", "").trim();
        boolean neg = t.startsWith("(") && t.endsWith(")");
        if (neg) t = t.substring(1, t.length() - 1);
        if (!t.matches("-?\\d+(\\.\\d+)?")) return null;
        BigDecimal v = new BigDecimal(t);
        return neg ? v.negate() : v;
    }

    static LocalDate date(String s) {
        if (s == null) return null;
        Matcher m = DATE.matcher(s);
        if (!m.find()) return null;
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
