package com.buildrisk.radar.filing.domain;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 정기보고서 'XI. 그 밖에 투자자 보호를 위하여 필요한 사항 — 2. 우발부채 등에 관한 사항'(AASSOCNOTE D-0-11-2-0)의
 * 부동산 PF 표준 서식을 구조화합니다 (ADR-024).
 * 표준 서식 = 금융감독원 「건설회사의 건설계약 관련 우발부채 주석공시 모범사례」(2024.1, 2023년도 재무제표부터 적용 권고)의
 * 종합요약표 · 사업장별 세부 내역. 의무 서식이 아니라 권고라 회사마다 도입 시점 · 모양이 다름 — 보증한도 = 최대 노출, 보증금액 = 현재 노출.
 *
 * <ul>
 *   <li>종합요약표: 구분(정비사업 · 기타사업) × 종류(브릿지론 · 본PF) — 보증한도 · 보증금액 · 대출잔액(당기 · 만기 구간 6개 · 전기)</li>
 *   <li>요약표는 단독사업 · 컨소시엄(전체) · 컨소시엄(회사분)으로 나뉨 — 회사 노출 = 단독 + 컨소시엄 회사분 (표 앞 문단으로 구분)</li>
 *   <li>세부 내역: 사업장마다 사업지역 · PF 종류 · 신용보강 종류 · 보증금액 · 대출잔액 · 만기 · 책임준공약정금액</li>
 *   <li>검증: 세부 내역 보증금액 합 ≈ 요약표 합계(±1%) — 맞지 않으면 reconciled=false 로 표시하고 요약표 값을 씀</li>
 * </ul>
 * 표준 서식은 2023 사업보고서부터(회사마다 도입 시점이 다름). 표가 없으면 추정하지 않고 NO_STANDARD.
 * DART 원문은 셀에 비표준 {@code <TE>} 태그를 써서 HTML 파서로 읽으면 표 밖으로 밀려나므로 XML 파서로 읽습니다.
 */
public final class PfExposureParser {
    public static final int VERSION = 13;
    private static final Pattern UNIT = Pattern.compile("단위\\s*[:：]?\\s*(?:[^)]*?,\\s*)?(백만원|천원|억원|원)");
    private static final Pattern NUM = Pattern.compile("^\\(?-?[\\d,]+(\\.\\d+)?\\)?%?$");
    /** 소계 줄의 병합 칸: '계' · '소계' · '컨소시엄 사업 계' · '정비사업 계(단독 사업+컨소시엄 사업)' · '컨소시움 계' (회사마다 표기가 다름) */
    private static final Pattern TOTAL_LABEL = Pattern.compile("^.{0,20}계(\\(.*\\))?$");
    private static final Pattern RELATED_INTRO = Pattern.compile("특수관계자(에게|에대한|의채무|에제공)");
    private static final Pattern FOOTNOTE = Pattern.compile("\\((\\*|주)\\s*\\d*\\)");
    // 만기 구간 열 — 회사마다 '6개월초과12개월이내' · '6개월초과1년이내' · '6개월~12개월' · '6월~12월이내' · '1년~2년' · '1~2년' 처럼
    // 표기가 다름 (468개 구간 실측). 3년 초과 열이 없거나 1~2년까지만 적은 회사도 있음
    private static final Pattern M3 = Pattern.compile("(?<!\\d)3개?월(이내|이하)"), M6 = Pattern.compile("3개?월(초과|~)6개?월"),
            M12 = Pattern.compile("6개?월(초과|~)(12개?월|1년)"), Y2 = Pattern.compile("(1년|12개?월)(초과|~)(2년|24개?월)|(?<!\\d)1~2년"),
            Y3 = Pattern.compile("2년(초과|~)3년|(?<!\\d)2~3년"), OVER3 = Pattern.compile("3년초과");
    private static final Pattern UPTO_6M = Pattern.compile("(?<!초과)(?<!~)(?<!\\d)6개?월(이내|이하)"),
            UPTO_1Y = Pattern.compile("(?<!초과)(?<!~)(?<!\\d)(1년|12개?월)(이내|이하)"),
            UPTO_2Y = Pattern.compile("(?<!초과)(?<!~)(?<!\\d)2년(이내|이하)"), UPTO_3Y = Pattern.compile("(?<!초과)(?<!~)(?<!\\d)3년(이내|이하)");
    private static final Pattern MATURITY_LABEL = Pattern.compile("(?<!\\d)\\d{1,2}(개월|월|년)");
    // 책임준공 약정만 따로 모은 표 — 표준 서식에서 PF 보증(신용보강)과 별도 항목이라 사업장 PF 보증에 넣지 않음 (까뮤이앤씨 · 범양건영)
    // '단독사업 외 컨소시엄참여사업은 없습니다' 같은 부정 문장 — 범위 꼬리표가 아님 (HS화성: 단독 표를 컨소시엄 전체로 보고 합계에서 뺐던 원인)
    private static final Pattern NO_CONSORTIUM = Pattern.compile("컨소시엄(참여)?(사업)?(은|에대한금액은|에대한신용보강은)?없");
    private static final Pattern SHARE_TAG = Pattern.compile("회사분|당사분|연결회사분|지분|부담분");
    private static final Pattern ALL_CELL = Pattern.compile("^(컨소시엄)?전체분?$"), SHARE_CELL = Pattern.compile("^(당사분|회사분|연결회사분|연결분|지분|부담분)$");
    // 보증금액 열 이름이 회사 몫임을 밝힌 경우 '보증금액(연결)' — 회사분 표 없이 이 표 하나만 낸 회사(한신공영)는 이것이 회사 노출
    private static final Pattern COMPANY_BASIS = Pattern.compile("^보증금액\\(?(연결|당사|회사)");
    // 원문 주석 '(주1) 기타사업은 … 직접 시행 · 시공하는 자체 사업 … 직접 PF 차주' — 회사 자신의 차입(이미 부채)이라 보증이 아님 (아이에스동서)
    private static final Pattern SELF_DEV_NOTE = Pattern.compile("\\((주|\\*)\\s*(\\d+)\\)[^()]{0,40}자체\\s*사업");
    // 표준 서식 본문 '연결회사는 보고기간 말에 PF와 관련하여 (보증금액 기준) 총 N백만원(전기말 …)의 신용보강을 제공' — 전기말 금액 · '없으며' 문장은 제외
    private static final Pattern STATED = Pattern.compile("PF(?:\\(대출\\))?[와과]관련(?:하여|한|된)?[^0-9전없]{0,15}?([\\d,]+)(백만원|천원|억원|원)(?:\\(전기말?[^)]*\\))?의신용(?:보강|을보강)");
    private static final Pattern NONE_TEXT = Pattern.compile("PF.{0,30}(신용보강|보증).{0,15}(없습니다|없음|제공하지않)");
    private static final Pattern COMPLETION_INTRO = Pattern.compile("책임준공(미이행시조건부채무인수)?약정(세부|현황)");

    private PfExposureParser() {}

    public enum Status { PARSED, NO_STANDARD, NONE }

    public enum Scope { SOLE, CONSORTIUM_ALL, CONSORTIUM_SHARE }

    /** 대출잔액 만기 구간 (3개월 이내 · 3~6개월 · 6~12개월 · 1~2년 · 2~3년 · 3년 초과) */
    public record Due(BigDecimal m3, BigDecimal m6, BigDecimal m12, BigDecimal y2, BigDecimal y3, BigDecimal over3) {
        /**
         * 12개월 안 만기 합. 구간 열을 알아본 표에서 '-' 는 0 (다른 구간에 금액이 있고 구간 합 = 대출잔액인 표 실측).
         * 여섯 구간이 모두 비면 알 수 없음(null) — 대출잔액은 있는데 구간을 하나도 안 적은 표(서한 2025)
         */
        public BigDecimal within12m() {
            if (m3 == null && m6 == null && m12 == null && y2 == null && y3 == null && over3 == null) return null;
            return nz(m3).add(nz(m6)).add(nz(m12));
        }

        private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

        Due minus(Due o) {
            return o == null ? this : plus(new Due(neg(o.m3), neg(o.m6), neg(o.m12), neg(o.y2), neg(o.y3), neg(o.over3)));
        }

        private static BigDecimal neg(BigDecimal v) { return v == null ? null : v.negate(); }

        Due plus(Due o) {
            return o == null ? this : new Due(sum(m3, o.m3), sum(m6, o.m6), sum(m12, o.m12), sum(y2, o.y2), sum(y3, o.y3), sum(over3, o.over3));
        }
    }

    public record SummaryRow(Scope scope, String category, String pfType, BigDecimal limit, BigDecimal guarantee,
                             BigDecimal balance, Due due, BigDecimal priorBalance, boolean total) {
        SummaryRow withScope(Scope s) { return new SummaryRow(s, category, pfType, limit, guarantee, balance, due, priorBalance, total); }
    }

    public record Site(int seq, String category, String region, String siteType, String pfType, String enhancement,
                       BigDecimal limitTotal, BigDecimal sharePct, BigDecimal guarantee, String debtor, Boolean related,
                       String lenderType, BigDecimal balance, BigDecimal balancePrior, String loanPeriod, String maturity,
                       String kind, BigDecimal completionAmount) {}

    /** 회사 노출(단독 + 컨소시엄 회사분) 합계 */
    public record Totals(BigDecimal limit, BigDecimal guarantee, BigDecimal balance, BigDecimal bridgeGuarantee,
                         BigDecimal bridgeBalance, Due due, BigDecimal priorBalance, BigDecimal sitesGuarantee, Boolean reconciled) {}

    /**
     * @param statedTotal 본문 서술의 합계 '…PF와 관련하여 총 N백만원의 신용보강을 제공' (원 단위, 없으면 null) — 표와 독립된 두 번째 대조 근거
     */
    public record Result(Status status, List<SummaryRow> summary, List<Site> sites, Totals totals, String message, BigDecimal statedTotal) {
        /** 본문 합계 ≈ 표 합계(±1%). 어긋나면 대개 원문 쪽 기준 차이(본문이 컨소시엄 전체분 · 대출잔액 · 보증한도를 씀 — 실측) */
        public Boolean statedMatch() {
            BigDecimal g = totals == null ? null : totals.guarantee();
            if (statedTotal == null || g == null) return null;
            if (statedTotal.signum() == 0) return g.signum() == 0;
            return g.subtract(statedTotal).abs().compareTo(statedTotal.abs().multiply(new BigDecimal("0.01"))) <= 0;
        }
    }

    public static Result parse(String sectionHtml) {
        Document doc = Jsoup.parse(sectionHtml == null ? "" : sectionHtml, "", Parser.xmlParser());
        List<SummaryRow> summary = new ArrayList<>();
        List<Site> sites = new ArrayList<>();
        java.util.Set<String> detailSignatures = new java.util.HashSet<>();
        StringBuilder context = new StringBuilder();          // 직전 큰 표 이후의 문단 · 작은 표(단위 줄) 글자
        int summaryIndex = 0;
        java.util.Set<SummaryRow> companyBasisAll = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Element el : doc.select("P, TABLE, p, table")) {
            if (isTable(el) && el.parents().stream().anyMatch(PfExposureParser::isTable)) continue;   // 중첩 표
            if (!isTable(el)) {
                if (el.parents().stream().anyMatch(PfExposureParser::isTable)) continue;
                String text = el.text();
                // 연결 블록 뒤에 회사(별도) 블록이 한 번 더 실린 보고서(계룡건설산업 '■ …의 그 밖의 우발채무 등') — 첫 블록(연결)만 씀.
                // 기업 지표가 연결 재무제표 우선이므로 같은 기준
                String flat = text.replaceAll("\\s+", "");
                if (!summary.isEmpty() && flat.contains("그밖의우발채무")) break;
                context.append(' ').append(text);
                continue;
            }
            List<List<String>> grid = grid(el);
            if (grid.size() <= 1) {                            // '(단위: 백만원)' 한 줄짜리 표
                grid.forEach(r -> context.append(' ').append(String.join(" ", r)));
                continue;
            }
            String ctx = context.toString();
            context.setLength(0);
            BigDecimal unit = unit(String.join(" ", grid.get(0)) + " " + ctx);
            int h = headerEnd(grid);
            if (h < 0) continue;
            List<String> cols = labels(grid, h);
            if (isSummary(cols)) {
                Scope sc = scope(ctx, summaryIndex++);
                List<SummaryRow> rows = summaryRows(grid, h, cols, sc, unit);
                int gc = find(cols, c -> c.startsWith("보증금액"));
                if (sc == Scope.CONSORTIUM_ALL && gc >= 0 && COMPANY_BASIS.matcher(cols.get(gc)).find()) companyBasisAll.addAll(rows);
                summary.addAll(rows);
            } else if (isDetail(cols) && !relatedTable(ctx) && !socTable(ctx) && !completionTable(ctx)) {
                // 특수관계자 표는 위 내역의 일부(이중 계산 방지), SOC 사업은 표준 서식의 PF 범위 밖('중도금대출, SOC 제외')
                List<Site> rows = siteRows(grid, h, cols, category(ctx), unit, sites.size());
                // 같은 사업장 목록이 한 번 더 실린 표(전기분 · 별도 기준 — 라벨 없이 금액만 조금 다름)는 건너뜀.
                // 행 단위로 지우면 채무자 · 지역이 같은 서로 다른 대출(태영건설)까지 지워져 표 단위로 판단
                String sig = rows.stream().map(x -> norm(x.debtor()) + "|" + norm(x.region())).reduce("", String::concat);
                if (!rows.isEmpty() && detailSignatures.add(sig)) sites.addAll(rows);
            }
        }
        if (summary.isEmpty() && sites.isEmpty()) {
            String text = doc.text().replaceAll("\\s+", "");
            // '해당사항 없음' 또는 'PF 관련 신용보강한 사항은 없습니다' · 'PF와 관련한 보증을 제공하지 않고' (까뮤이앤씨 · 범양건영)
            boolean none = text.contains("해당사항없") && !text.contains("신용보강") || NONE_TEXT.matcher(text).find();
            return new Result(none ? Status.NONE : Status.NO_STANDARD, List.of(), List.of(), null,
                    none ? "PF 우발부채 해당사항 없음" : "부동산 PF 종합요약표(금감원 주석공시 모범사례) 없음 — 2023년도 재무제표 이전이거나 회사 고유 서식", null);
        }
        // 회사분 표가 따로 없고 '컨소시엄(전체)' 표의 보증금액이 회사 몫(연결)이면 그 표가 회사분
        if (!companyBasisAll.isEmpty() && summary.stream().noneMatch(r -> r.scope() == Scope.CONSORTIUM_SHARE)) {
            summary.replaceAll(r -> companyBasisAll.contains(r) ? r.withScope(Scope.CONSORTIUM_SHARE) : r);
        }
        var sd = SELF_DEV_NOTE.matcher(doc.text().replaceAll("\\s+", ""));
        String selfMarker = sd.find() ? "(" + sd.group(1) + sd.group(2) + ")" : null;
        Totals totals = totals(summary, sites, selfMarker);
        String message = selfMarker != null && summary.stream().anyMatch(r -> !r.total() && r.category() != null && r.category().contains(selfMarker))
                ? "원문 주석 " + selfMarker + ": 자체사업(회사가 직접 PF 차주 — 이미 부채로 반영)은 보증 합계에서 뺌"
                : summary.isEmpty() ? "부동산 PF 종합요약표를 찾지 못함 — 사업장별 내역만 (회사 합계 · 비율 없음)"
                : totals.due() == null ? "만기 구간 열을 알아보지 못함 — 만기 구간 · 12개월 내 만기 없음"
                : totals.due().within12m() == null ? "만기 구간이 모두 비어 있음 (대출잔액만 기재) — 12개월 내 만기 없음" : null;
        return new Result(Status.PARSED, summary, sites, totals, message, statedTotal(doc.text()));
    }

    /** 본문 서술 합계 (원 단위) — 없으면 null */
    public static BigDecimal statedTotal(String text) {
        Matcher m = STATED.matcher(text.replaceAll("\\s+", ""));
        if (!m.find()) return null;
        return new BigDecimal(m.group(1).replace(",", "")).multiply(unit("단위: " + m.group(2)));
    }

    // ------------------------------------------------------------------ 요약표

    private static boolean isSummary(List<String> cols) {
        return has(cols, "구분") && has(cols, "종류") && has(cols, "보증한도") && has(cols, "보증금액") && has(cols, "대출잔액")
                && !has(cols, "사업지역");
    }

    private static List<SummaryRow> summaryRows(List<List<String>> grid, int h, List<String> cols, Scope scope, BigDecimal unit) {
        int limit = find(cols, c -> c.startsWith("보증한도")), guarantee = find(cols, c -> c.startsWith("보증금액"));
        List<Integer> balanceCols = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) if (cols.get(i).startsWith("대출잔액")) balanceCols.add(i);
        // 당기 · 전기 대출잔액 열 = '대출잔액' 열 중 만기 구간이 아닌 것 (구간을 못 알아본 열을 잔액으로 잘못 고르지 않게 라벨로도 거름)
        List<Integer> plain = balanceCols.stream().filter(i -> !MATURITY_LABEL.matcher(cols.get(i)).find()).toList();
        int current = plain.isEmpty() ? -1 : plain.getFirst();
        int prior = plain.size() < 2 ? -1 : plain.getLast();
        int[] b = buckets(grid, h, cols, current, unit);                 // m3 · m6 · m12 · y2 · y3 · over3, 못 알아보면 null
        int labelEnd = Math.max(1, limit >= 0 ? limit : guarantee);
        // 한 표 안에서 줄마다 '전체분 · 당사분'을 나눈 서식(금호건설 컨소시엄 표) — 줄의 범위를 따로 매기고 합계 줄 대신 범위별로 더함
        // (KCC건설은 단독 줄 · 컨소시엄 '전체 · 당사분' 줄 · 소계를 한 표에) — 칸 전체가 꼬리표인 경우만 봄
        java.util.function.Function<List<String>, Scope> rowScope = row -> {
            for (int k = 0; k < Math.min(labelEnd, row.size()); k++) {
                String cell = norm(row.get(k));
                if (ALL_CELL.matcher(cell).matches()) return Scope.CONSORTIUM_ALL;
                if (SHARE_CELL.matcher(cell).matches()) return Scope.CONSORTIUM_SHARE;
            }
            return scope;
        };
        boolean perRow = false;
        for (int r = h; r < grid.size(); r++) perRow |= rowScope.apply(grid.get(r)) != scope;
        List<SummaryRow> out = new ArrayList<>();
        String category = null;
        for (int r = h; r < grid.size(); r++) {
            List<String> row = grid.get(r);
            String c0 = norm(get(row, 0)), c1 = norm(get(row, 1));
            boolean total = c0.contains("합계") || c0.contains("총계");
            if (!total && !c0.isEmpty()) category = c0.replace("소계", "").replace("계", "");
            // 구분 소계: '정비사업 소계' · '계' · '단독사업계' · '컨소시엄계(*)' 처럼 '…계' 로 끝나는 첫 칸, 또는 둘째 칸이 '계'
            boolean subtotal = c0.contains("소계") || TOTAL_LABEL.matcher(c0).matches() || c1.equals("계") || c1.equals("소계");
            if (subtotal && !total) continue;                        // 구분 소계는 합계와 중복
            if (total && perRow) continue;                           // 전체분 + 당사분이 섞인 합계 줄은 쓰지 않음
            BigDecimal g = num(get(row, guarantee), unit);
            if (g == null && num(get(row, current), unit) == null && num(get(row, limit), unit) == null) continue;
            out.add(new SummaryRow(perRow ? rowScope.apply(row) : scope, total ? null : category, total ? null : pfType(row, labelEnd),
                    num(get(row, limit), unit), g, num(get(row, current), unit),
                    b == null ? null : new Due(num(get(row, b[0]), unit), num(get(row, b[1]), unit), num(get(row, b[2]), unit),
                            num(get(row, b[3]), unit), num(get(row, b[4]), unit), num(get(row, b[5]), unit)),
                    num(get(row, prior), unit), total));
        }
        // 합계 줄이 없는 표(또는 줄마다 범위가 다른 표)는 범위별로 행을 더해 합계를 만듦
        for (Scope sc : Scope.values()) {
            List<SummaryRow> rows = out.stream().filter(x -> x.scope() == sc).toList();
            if (rows.isEmpty() || rows.stream().anyMatch(SummaryRow::total)) continue;
            out.add(rows.stream().reduce(new SummaryRow(sc, null, null, null, null, null, null, null, true),
                    (a, x) -> new SummaryRow(sc, null, null, sum(a.limit(), x.limit()), sum(a.guarantee(), x.guarantee()),
                            sum(a.balance(), x.balance()), x.due() == null ? a.due() : x.due().plus(a.due()),
                            sum(a.priorBalance(), x.priorBalance()), true)));
        }
        return out;
    }

    /** PF 종류 칸 — 보통 두 번째 칸이지만 '전체분 · 당사분' 칸이 끼어든 서식은 '본PF · 브릿지론' 이 적힌 칸을 찾음 */
    private static String pfType(List<String> row, int labelEnd) {
        for (int k = 1; k < Math.min(labelEnd, row.size()); k++) {
            String t = norm(row.get(k));
            if (t.contains("PF") || t.contains("브릿지")) return row.get(k);
        }
        return get(row, 1);
    }

    /**
     * 만기 구간 열 [m3, m6, m12, y2, y3, over3] — 12개월 안의 세 구간을 모두 알아봐야 씀 (일부만 더해 '12개월 내 만기'를 적게 보이지 않게).
     * '3개월이내 · 6개월이내 · 1년이내 · 2년이내 …' 처럼 누적인지 구간인지 이름만으로 알 수 없는 서식(금호건설)은
     * 모든 줄에서 구간 합 = 대출잔액(±1%)이고 '만기경과' 금액이 없을 때만 구간으로 인정 — 그 밖('3년이내' 한 칸 · 일부 구간만)은 비움
     */
    private static int[] buckets(List<List<String>> grid, int h, List<String> cols, int current, BigDecimal unit) {
        int m3 = find(cols, c -> M3.matcher(c).find()), m6 = find(cols, c -> M6.matcher(c).find()),
                m12 = find(cols, c -> M12.matcher(c).find()), y2 = find(cols, c -> Y2.matcher(c).find()),
                y3 = find(cols, c -> Y3.matcher(c).find()), over3 = find(cols, c -> OVER3.matcher(c).find());
        if (m3 >= 0 && m6 >= 0 && m12 >= 0) return new int[]{m3, m6, m12, y2, y3, over3};
        int a6 = find(cols, c -> UPTO_6M.matcher(c).find()), a12 = find(cols, c -> UPTO_1Y.matcher(c).find()),
                a2 = find(cols, c -> UPTO_2Y.matcher(c).find()), a3 = find(cols, c -> UPTO_3Y.matcher(c).find()),
                overdue = find(cols, c -> c.contains("만기경과"));
        if (m3 < 0 || a6 < 0 || a12 < 0 || current < 0) return null;
        int[] cand = {m3, a6, a12, a2, a3, over3};
        boolean checked = false;
        for (int r = h; r < grid.size(); r++) {
            List<String> row = grid.get(r);
            BigDecimal bal = num(get(row, current), unit);
            if (bal == null || bal.signum() == 0) continue;
            if (overdue >= 0 && num(get(row, overdue), unit) != null && num(get(row, overdue), unit).signum() != 0) return null;
            BigDecimal s = BigDecimal.ZERO;
            for (int c : cand) if (c >= 0 && num(get(row, c), unit) != null) s = s.add(num(get(row, c), unit));
            if (s.subtract(bal).abs().compareTo(bal.abs().multiply(new BigDecimal("0.01"))) > 0) return null;
            checked = true;
        }
        return checked ? cand : null;
    }

    /** 테스트용 — 표 앞 글자로 범위 판정 */
    public static Scope scopeOf(String ctx, int index) { return scope(ctx, index); }

    /** 표 앞 문단으로 단독 · 컨소시엄(전체) · 컨소시엄(회사분) 구분. 표지가 없으면 순서(①②③)로 */
    static Scope scope(String ctx, int index) {
        String c = NO_CONSORTIUM.matcher(ctx.replaceAll("\\s+", "").replace("컨소시움", "컨소시엄")).replaceAll("");
        String t = c.substring(Math.max(0, c.length() - 60));       // 표 바로 앞의 짧은 꼬리표만 봄 (긴 설명 문단의 단어에 끌리지 않게)
        // 마지막에 나온 꼬리표가 이 표의 것 — '이 중 단독 …, 컨소시엄 … 입니다. ① 단독사업' 같은 문장 뒤의 꼬리표
        int sole = t.lastIndexOf("단독"), cons = t.lastIndexOf("컨소시엄");
        if (sole >= 0 && sole > cons) return Scope.SOLE;
        if (cons >= 0) {
            String after = t.substring(cons);
            if (SHARE_TAG.matcher(after).find()) return Scope.CONSORTIUM_SHARE;
            return after.contains("전체") ? Scope.CONSORTIUM_ALL : Scope.CONSORTIUM_SHARE;
        }
        if (t.contains("전체")) return Scope.CONSORTIUM_ALL;
        return switch (index) {
            case 0 -> Scope.SOLE;
            case 1 -> Scope.CONSORTIUM_ALL;
            default -> Scope.CONSORTIUM_SHARE;
        };
    }

    // ------------------------------------------------------------------ 세부 내역

    private static boolean isDetail(List<String> cols) {
        return has(cols, "사업지역") && has(cols, "신용보강") && has(cols, "보증금액") && has(cols, "대출잔액");
    }

    private static List<Site> siteRows(List<List<String>> grid, int h, List<String> cols, String category, BigDecimal unit, int seq0) {
        int region = find(cols, c -> c.startsWith("사업지역")), type = find(cols, c -> c.startsWith("사업") && c.contains("구분")),
                pf = find(cols, c -> c.startsWith("PF종류")), enh = find(cols, c -> c.startsWith("신용보강")),
                limit = find(cols, c -> c.startsWith("보증한도")), share = find(cols, c -> c.startsWith("부담률")),
                guarantee = find(cols, c -> c.startsWith("보증금액")),
                debtor = find(cols, c -> c.startsWith("채무자") && !c.contains("특수관계")),
                related = find(cols, c -> c.startsWith("채무자") && c.contains("특수관계")),
                lender = find(cols, c -> c.startsWith("채권기관")), period = find(cols, c -> c.startsWith("대출기간")),
                maturity = find(cols, c -> c.startsWith("만기일")), kind = find(cols, c -> c.startsWith("유형")),
                completion = find(cols, c -> c.startsWith("책임준공"));
        List<Integer> balances = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) if (cols.get(i).startsWith("대출잔액")) balances.add(i);
        List<Site> out = new ArrayList<>();
        for (int r = h; r < grid.size(); r++) {
            List<String> row = grid.get(r);
            String joined = norm(String.join("", row));
            if (joined.contains("조기상환조항") || joined.contains("기타사항") || joined.contains("소계") || joined.contains("합계")
                    || joined.matches(".*(단독사업|컨소시엄)계.*")) continue;
            BigDecimal g = num(get(row, guarantee), unit);
            String reg = get(row, region);
            if (g == null || reg == null || NUM.matcher(norm(reg)).matches()) continue;
            // 소계 줄: 사업지역~신용보강 칸이 병합되어 '…계' 만 있음 (HL D&I · DL이앤씨 · 남광토건 — 세부 합이 요약표의 2배가 됐던 원인).
            // 사업지역과 PF 종류 칸이 같은 글자(병합)일 때만 — '인천 계양구' 같은 지명은 건드리지 않음
            String pfText = get(row, pf);
            if (TOTAL_LABEL.matcher(norm(reg)).matches() && (pfText == null || norm(pfText).equals(norm(reg)))) continue;
            reg = FOOTNOTE.matcher(reg).replaceAll("").trim();
            String rel = get(row, related);
            out.add(new Site(seq0 + out.size() + 1, category, reg, get(row, type), get(row, pf), get(row, enh),
                    num(get(row, limit), unit), pct(get(row, share)), g, get(row, debtor),
                    rel == null ? null : rel.matches("(?i)^(O|○|Y|예|해당)$"), get(row, lender),
                    balances.isEmpty() ? null : num(get(row, balances.get(0)), unit),
                    balances.size() < 2 ? null : num(get(row, balances.get(1)), unit),
                    get(row, period), get(row, maturity), get(row, kind), num(get(row, completion), unit)));
        }
        return out;
    }

    /**
     * 특수관계자에게 제공한 보증만 모은 표인지 (위 내역의 일부라 더하면 이중 계산). 표 바로 앞 소개 문장만 봄 —
     * 앞선 설명 문단에 '특수관계자'가 나왔다고 일반 세부 표까지 건너뛰던 문제(서희건설)
     */
    static boolean relatedTable(String ctx) {
        String t = ctx.replaceAll("\\s+", "");
        t = t.substring(Math.max(0, t.length() - 200));
        return RELATED_INTRO.matcher(t).find();
    }

    /** SOC 사업 표인지 — 표준 서식 제목의 '(중도금대출, SOC사업 제외)' 는 SOC 표가 아님 */
    static boolean socTable(String ctx) {
        String t = ctx.replaceAll("\\s+", "");
        t = t.substring(Math.max(0, t.length() - 150));             // 표 바로 앞 소개 문장만 (앞 문단의 'SOC 는 제외' 설명에 끌리지 않게 — 서희건설)
        return t.contains("SOC") && !t.contains("제외") && !t.contains("미포함");
    }

    /** 책임준공 약정 표인지 — 표 바로 앞 소개 문장만 봄 (PF 보증 표 설명의 '책임준공 미이행에 따른 채무인수' 같은 말에 끌리지 않게) */
    static boolean completionTable(String ctx) {
        String t = ctx.replaceAll("\\s+", "");
        return COMPLETION_INTRO.matcher(t.substring(Math.max(0, t.length() - 150))).find();
    }

    /** 표 앞 문단에서 마지막으로 나온 쪽 — '① 정비사업 … (긴 설명) … (단위: 백만원)' 처럼 꼬리표와 표 사이에 문장이 끼어도 */
    private static String category(String ctx) {
        int a = ctx.lastIndexOf("정비사업"), b = ctx.lastIndexOf("기타사업");
        return a < 0 && b < 0 ? null : a > b ? "정비사업" : "기타사업";
    }

    // ------------------------------------------------------------------ 합계 · 검증

    private static Totals totals(List<SummaryRow> summary, List<Site> sites, String selfMarker) {
        // 같은 범위의 요약표가 두 번 나오면(연결 · 별도 등 — KCC건설은 같은 표가 두 번) 첫 표만: 합계 줄이 범위마다 하나
        java.util.Set<Scope> seenTotal = java.util.EnumSet.noneOf(Scope.class);
        List<SummaryRow> mine = new ArrayList<>();
        java.util.Set<Scope> closed = java.util.EnumSet.noneOf(Scope.class);
        for (SummaryRow s : summary) {
            if (s.scope() == Scope.CONSORTIUM_ALL || closed.contains(s.scope())) continue;
            mine.add(s);
            if (s.total() && seenTotal.add(s.scope())) closed.add(s.scope());
        }
        BigDecimal limit = null, guarantee = null, balance = null, prior = null, bridgeG = null, bridgeB = null;
        Due due = null;
        for (SummaryRow s : mine) {
            if (s.total()) {
                limit = sum(limit, s.limit());
                guarantee = sum(guarantee, s.guarantee());
                balance = sum(balance, s.balance());
                prior = sum(prior, s.priorBalance());
                due = due == null ? s.due() : due.plus(s.due());
            } else if (!self(s, selfMarker) && s.pfType() != null && norm(s.pfType()).contains("브릿지")) {
                bridgeG = sum(bridgeG, s.guarantee());
                bridgeB = sum(bridgeB, s.balance());
            }
        }
        // 자체사업 행은 합계 줄에 들어 있으므로 합계를 다 더한 뒤 빼냄 (원문 주석이 보증이 아니라고 밝힘)
        for (SummaryRow s : mine) {
            if (s.total() || !self(s, selfMarker)) continue;
            limit = sub(limit, s.limit());
            guarantee = sub(guarantee, s.guarantee());
            balance = sub(balance, s.balance());
            prior = sub(prior, s.priorBalance());
            if (due != null) due = due.minus(s.due());
        }
        BigDecimal sitesG = sites.stream().map(Site::guarantee).reduce(null, PfExposureParser::sum);
        Boolean reconciled = guarantee == null || sitesG == null || guarantee.signum() == 0 ? null
                : sitesG.subtract(guarantee).abs().compareTo(guarantee.abs().multiply(new BigDecimal("0.01"))) <= 0;
        return new Totals(limit, guarantee, balance, bridgeG, bridgeB, due, prior, sitesG, reconciled);
    }

    // ------------------------------------------------------------------ 표 → 격자

    private static boolean isTable(Element e) { return e.normalName().equals("table"); }

    /** rowspan · colspan 을 펼친 격자 (병합된 칸은 같은 글자로 채움) */
    static List<List<String>> grid(Element table) {
        List<List<String>> out = new ArrayList<>();
        java.util.Map<Integer, Integer> left = new java.util.HashMap<>();      // 열 → 위에서 내려오는 rowspan 남은 줄 수
        java.util.Map<Integer, String> carried = new java.util.HashMap<>();
        for (Element tr : table.select("TR, tr")) {
            if (tr.parents().stream().filter(PfExposureParser::isTable).findFirst().orElse(null) != table) continue;   // 중첩 표의 줄
            List<String> row = new ArrayList<>();
            int c = 0;
            for (Element cell : tr.children()) {
                String n = cell.normalName();
                if (!n.equals("td") && !n.equals("th") && !n.equals("te")) continue;
                c = carry(row, c, left, carried);
                String text = cell.text().replace(' ', ' ').trim();
                int cs = span(cell, "COLSPAN"), rs = span(cell, "ROWSPAN");
                for (int k = 0; k < cs; k++) {
                    set(row, c + k, text);
                    if (rs > 1) {
                        left.put(c + k, rs - 1);
                        carried.put(c + k, text);
                    }
                }
                c += cs;
            }
            // 줄 끝 뒤에 남은 rowspan 칸
            int max = left.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
            for (int col = c; col <= max; col++) {
                Integer k = left.get(col);
                if (k == null) continue;
                set(row, col, carried.get(col));
                if (k == 1) {
                    left.remove(col);
                    carried.remove(col);
                } else {
                    left.put(col, k - 1);
                }
            }
            out.add(row);
        }
        return out;
    }

    /** c 열부터 위에서 내려온 rowspan 칸을 채우고 다음 빈 열 번호를 돌려줌 */
    private static int carry(List<String> row, int c, java.util.Map<Integer, Integer> left, java.util.Map<Integer, String> carried) {
        while (left.getOrDefault(c, 0) > 0) {
            set(row, c, carried.get(c));
            int k = left.get(c) - 1;
            if (k == 0) {
                left.remove(c);
                carried.remove(c);
            } else {
                left.put(c, k);
            }
            c++;
        }
        return c;
    }

    /** 숫자가 둘 이상 나오는 첫 줄 = 데이터 시작. 그 위가 머리 */
    private static int headerEnd(List<List<String>> grid) {
        for (int r = 0; r < grid.size(); r++) {
            long nums = grid.get(r).stream().filter(v -> v != null && NUM.matcher(norm(v)).matches() && !norm(v).matches("^\\d{4}\\.?\\d{0,2}\\.?\\d{0,2}$")).count();
            if (nums >= 2 && r > 0) return r;
        }
        return -1;
    }

    /** 열마다 머리 줄 글자를 위에서부터 이어 붙인 이름 (병합으로 같은 글자가 반복되면 한 번만) */
    private static List<String> labels(List<List<String>> grid, int h) {
        int width = grid.stream().limit(h).mapToInt(List::size).max().orElse(0);
        List<String> out = new ArrayList<>();
        for (int c = 0; c < width; c++) {
            StringBuilder sb = new StringBuilder();
            String last = null;
            for (int r = 0; r < h; r++) {
                String v = norm(get(grid.get(r), c));
                if (v.isEmpty() || v.startsWith("(단위") || v.equals(last)) continue;
                sb.append(sb.isEmpty() ? "" : "|").append(v);
                last = v;
            }
            out.add(sb.toString());
        }
        return out;
    }

    // ------------------------------------------------------------------ 값

    public static BigDecimal unit(String text) {
        Matcher m = UNIT.matcher(text == null ? "" : text);
        String u = m.find() ? m.group(1) : "백만원";               // 표준 서식의 기본 단위
        return switch (u) {
            case "원" -> BigDecimal.ONE;
            case "천원" -> BigDecimal.valueOf(1_000);
            case "억원" -> BigDecimal.valueOf(100_000_000);
            default -> BigDecimal.valueOf(1_000_000);
        };
    }

    private static BigDecimal num(String s, BigDecimal unit) {
        BigDecimal v = FilingParser.amount(s == null ? null : s.replace(" ", ""));
        return v == null ? null : v.multiply(unit);
    }

    private static BigDecimal pct(String s) {
        return s == null ? null : FilingParser.amount(s.replace(" ", ""));
    }

    private static String get(List<String> row, int i) {
        if (i < 0 || i >= row.size()) return null;
        String v = row.get(i);
        return v == null || v.isBlank() || v.trim().equals("-") ? null : v.trim();
    }

    private static void set(List<String> row, int i, String v) {
        while (row.size() <= i) row.add(null);
        if (row.get(i) == null) row.set(i, v);
    }

    /**
     * 병합 칸 수 — HTML 표준 상한(colspan 1000 · rowspan 65534)으로 자름. 회사가 쓴 원문의 COLSPAN="2147483647" 하나로
     * 격자를 펼치다 메모리를 다 써 worker 가 죽었음(-XX:+ExitOnOutOfMemoryError, VERIFICATION 132)
     */
    private static int span(Element cell, String attr) {
        String v = cell.attr(attr).isEmpty() ? cell.attr(attr.toLowerCase()) : cell.attr(attr);
        try {
            return Math.clamp(Integer.parseInt(v.trim()), 1, "COLSPAN".equalsIgnoreCase(attr) ? 1000 : 65534);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    static String norm(String s) { return s == null ? "" : s.replaceAll("[\\s ]", "").replaceAll("\\(\\*\\d*\\)", ""); }

    private static boolean has(List<String> cols, String key) { return cols.stream().anyMatch(c -> c.contains(key)); }

    private static int find(List<String> cols, Function<String, Boolean> p) {
        for (int i = 0; i < cols.size(); i++) if (p.apply(cols.get(i))) return i;
        return -1;
    }

    static BigDecimal sum(BigDecimal a, BigDecimal b) { return a == null ? b : b == null ? a : a.add(b); }

    private static boolean self(SummaryRow s, String marker) {
        return marker != null && s.category() != null && s.category().contains(marker);
    }

    static BigDecimal sub(BigDecimal a, BigDecimal b) { return a == null || b == null ? a : a.subtract(b); }
}
