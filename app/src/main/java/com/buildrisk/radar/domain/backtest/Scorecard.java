package com.buildrisk.radar.domain.backtest;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 경보 성적표 (ADR-023) — 규칙이 실제 부실 사건(회생 · 워크아웃 · 부도 · 감사의견 비적정)을 얼마나 미리 잡았는지.
 * DB 없이 계산하는 순수 함수라 단위 테스트로 고정합니다.
 *
 * <ul>
 *   <li>신호(signal): 규칙 판정 하나와 그 판정을 알 수 있게 된 날(근거 공시 접수일 — point-in-time)</li>
 *   <li>에피소드: 같은 기업 · 규칙의 신호가 {@link #EPISODE_GAP_DAYS} 안에 이어지면 하나 (분기마다 반복되는 같은 경보를 여러 번 세지 않음)</li>
 *   <li>적중(HIT): 에피소드의 마지막 신호 뒤 H개월 안에 사건 (경보가 켜져 있는 동안 사건이 오면 적중) ·
 *       빗나감(MISS): 마지막 신호 뒤 H개월이 지났는데 사건 없음 · 판정 보류(PENDING): 아직 H개월이 안 지남.
 *       시작일 기준으로 판정하면 2년 동안 켜져 있다가 부실로 이어진 경보를 '빗나감'으로 세게 됨 (실데이터에서 확인)</li>
 *   <li>선행 기간: 사건일 − 에피소드 시작일 (처음 경고한 때부터)</li>
 *   <li>사건 뒤(AFTER_EVENT): 이미 부실에 들어선 뒤의 경보 — 정밀도 계산에서 뺌</li>
 *   <li>재현율: 사건 전 H개월 안에 신호가 있었던 사건 / 평가할 수 있는 사건</li>
 * </ul>
 */
public final class Scorecard {
    /** 분기 판정이 한 분기 빠져도(≈ 6개월) 같은 에피소드로 봄 */
    public static final int EPISODE_GAP_DAYS = 200;
    /** 사건 전에 이만큼의 관측 기간이 없으면 '평가 불가' */
    public static final int MIN_OBSERVATION_DAYS = 90;
    public static final String ANY = "ANY";

    private Scorecard() {}

    public record Signal(String ruleCode, String corpCode, String period, LocalDate date) {}

    public record CreditEvent(String corpCode, LocalDate date, String kind, String rceptNo, String reportNm) {}

    public enum Outcome { HIT, MISS, PENDING, AFTER_EVENT }

    public record Episode(String ruleCode, String corpCode, LocalDate start, LocalDate last, int signals,
                          Outcome outcome, LocalDate eventDate, Integer leadDays) {}

    public record Catch(String ruleCode, String corpCode, LocalDate eventDate, boolean evaluable, boolean caught,
                        LocalDate firstSignal, Integer leadDays) {}

    public record Interval(double low, double high) {}

    public record RuleScore(String ruleCode, LocalDate observedFrom, int episodes, int hits, int misses, int pending, int afterEvent,
                            Double precision, Interval precisionCi, Double lift,
                            int events, int caught, Double recall, Interval recallCi, Integer medianLeadDays) {}

    public record Result(int horizonMonths, LocalDate observedFrom, LocalDate observedTo, Double baseRate, int baseSamples,
                         List<RuleScore> rules, List<Episode> episodes, List<Catch> catches) {}

    /**
     * @param ruleCodes   평가할 규칙 (표시 순서). {@link #ANY} = 아무 규칙이나 하나라도
     * @param corps       유니버스 기업 전체 (기준선 계산용)
     * @param events      기업별 첫 부실 사건
     * @param observedFrom 판정을 처음 만들 수 있는 날 (가장 이른 재무 공시)
     */
    public static Result score(List<String> ruleCodes, List<Signal> signals, Collection<String> corps,
                               Map<String, CreditEvent> events, LocalDate observedFrom, LocalDate observedTo, int horizonMonths) {
        return score(ruleCodes, signals, corps, events, observedFrom, Map.of(), observedTo, horizonMonths);
    }

    /**
     * @param ruleFrom 규칙별 관측 시작 (그 규칙의 지표가 처음 생긴 날 — 예: PF 표준 서식은 2023 사업보고서부터).
     *                 그보다 이른 사건은 그 규칙으로는 '평가 불가'. 없으면 observedFrom
     */
    public static Result score(List<String> ruleCodes, List<Signal> signals, Collection<String> corps, Map<String, CreditEvent> events,
                               LocalDate observedFrom, Map<String, LocalDate> ruleFrom, LocalDate observedTo, int horizonMonths) {
        List<Episode> allEpisodes = new ArrayList<>();
        List<Catch> allCatches = new ArrayList<>();
        Double base = baseRate(corps, events, observedFrom, observedTo, horizonMonths);
        int baseN = baseSamples(corps, events, observedFrom, observedTo, horizonMonths);
        List<RuleScore> scores = new ArrayList<>();
        for (String rule : ruleCodes) {
            List<Signal> mine = signals.stream().filter(s -> ANY.equals(rule) || s.ruleCode().equals(rule))
                    .map(s -> ANY.equals(rule) ? new Signal(ANY, s.corpCode(), s.period(), s.date()) : s).toList();
            List<Episode> eps = episodes(mine, events, observedTo, horizonMonths);
            LocalDate from = ruleFrom.getOrDefault(rule, observedFrom);
            List<Catch> catches = catches(rule, mine, events, from, horizonMonths);
            allEpisodes.addAll(eps);
            allCatches.addAll(catches);
            int hits = count(eps, Outcome.HIT), misses = count(eps, Outcome.MISS);
            Double precision = hits + misses == 0 ? null : (double) hits / (hits + misses);
            List<Catch> evaluable = catches.stream().filter(Catch::evaluable).toList();
            int caught = (int) evaluable.stream().filter(Catch::caught).count();
            Double recall = evaluable.isEmpty() ? null : (double) caught / evaluable.size();
            List<Integer> leads = evaluable.stream().filter(Catch::caught).map(Catch::leadDays).sorted().toList();
            scores.add(new RuleScore(rule, from, eps.size(), hits, misses, count(eps, Outcome.PENDING), count(eps, Outcome.AFTER_EVENT),
                    precision, wilson(hits, hits + misses), precision == null || base == null || base == 0 ? null : precision / base,
                    evaluable.size(), caught, recall, wilson(caught, evaluable.size()), median(leads)));
        }
        return new Result(horizonMonths, observedFrom, observedTo, base, baseN, scores, allEpisodes, allCatches);
    }

    /** 같은 기업 · 규칙의 신호를 에피소드로 묶고 결과를 판정 */
    static List<Episode> episodes(List<Signal> signals, Map<String, CreditEvent> events, LocalDate observedTo, int horizonMonths) {
        Map<String, List<Signal>> byCorp = new LinkedHashMap<>();
        signals.stream().sorted(Comparator.comparing(Signal::date))
                .forEach(s -> byCorp.computeIfAbsent(s.corpCode(), k -> new ArrayList<>()).add(s));
        List<Episode> out = new ArrayList<>();
        byCorp.forEach((corp, list) -> {
            CreditEvent ev = events.get(corp);
            int i = 0;
            while (i < list.size()) {
                Signal first = list.get(i);
                Signal last = first;
                int n = 1;
                while (i + n < list.size() && ChronoUnit.DAYS.between(last.date(), list.get(i + n).date()) <= EPISODE_GAP_DAYS) {
                    last = list.get(i + n);
                    n++;
                }
                out.add(judge(first, last, n, ev, observedTo, horizonMonths));
                i += n;
            }
        });
        return out;
    }

    private static Episode judge(Signal first, Signal last, int n, CreditEvent ev, LocalDate observedTo, int horizonMonths) {
        LocalDate start = first.date();
        LocalDate deadline = last.date().plusMonths(horizonMonths);
        Outcome o;
        Integer lead = null;
        if (ev != null && !ev.date().isAfter(start)) o = Outcome.AFTER_EVENT;              // 이미 부실에 들어선 뒤
        else if (ev != null && !ev.date().isAfter(deadline)) {
            o = Outcome.HIT;
            lead = (int) ChronoUnit.DAYS.between(start, ev.date());
        } else if (deadline.isAfter(observedTo)) o = Outcome.PENDING;                       // 아직 판정할 수 없음
        else o = Outcome.MISS;
        return new Episode(first.ruleCode(), first.corpCode(), start, last.date(), n, o, ev == null ? null : ev.date(), lead);
    }

    /** 사건마다: 사건 전 H개월 안에 신호가 있었는지, 그 신호가 속한 에피소드가 언제 시작됐는지 */
    static List<Catch> catches(String rule, List<Signal> signals, Map<String, CreditEvent> events, LocalDate observedFrom,
                               int horizonMonths) {
        List<Catch> out = new ArrayList<>();
        for (CreditEvent ev : events.values()) {
            boolean evaluable = ChronoUnit.DAYS.between(observedFrom, ev.date()) >= MIN_OBSERVATION_DAYS;
            List<Signal> before = signals.stream().filter(s -> s.corpCode().equals(ev.corpCode()) && s.date().isBefore(ev.date()))
                    .sorted(Comparator.comparing(Signal::date)).toList();
            LocalDate windowStart = ev.date().minusMonths(horizonMonths);
            boolean caught = before.stream().anyMatch(s -> !s.date().isBefore(windowStart));
            LocalDate first = null;
            if (caught) {
                // 창 안의 마지막 신호에서 거꾸로, 간격이 EPISODE_GAP_DAYS 를 넘기 전까지가 같은 에피소드 → 그 시작이 실제 선행 시점
                int k = before.size() - 1;
                while (k > 0 && ChronoUnit.DAYS.between(before.get(k - 1).date(), before.get(k).date()) <= EPISODE_GAP_DAYS) k--;
                first = before.get(k).date();
            }
            out.add(new Catch(rule, ev.corpCode(), ev.date(), evaluable, caught, first,
                    first == null ? null : (int) ChronoUnit.DAYS.between(first, ev.date())));
        }
        return out;
    }

    /**
     * 기준선: 아무 기업이나 아무 분기 말에 골랐을 때 H개월 안에 부실 사건이 나는 비율.
     * 정밀도를 이것과 비교해야 '경보가 울린 기업이 평균보다 얼마나 더 위험한가(lift)'를 말할 수 있음.
     */
    static Double baseRate(Collection<String> corps, Map<String, CreditEvent> events, LocalDate from, LocalDate to, int horizonMonths) {
        int[] hn = baseCounts(corps, events, from, to, horizonMonths);
        return hn[1] == 0 ? null : (double) hn[0] / hn[1];
    }

    static int baseSamples(Collection<String> corps, Map<String, CreditEvent> events, LocalDate from, LocalDate to, int horizonMonths) {
        return baseCounts(corps, events, from, to, horizonMonths)[1];
    }

    private static int[] baseCounts(Collection<String> corps, Map<String, CreditEvent> events, LocalDate from, LocalDate to,
                                    int horizonMonths) {
        int hits = 0, n = 0;
        for (LocalDate t = from; !t.plusMonths(horizonMonths).isAfter(to); t = t.plusMonths(3)) {
            for (String corp : corps) {
                CreditEvent ev = events.get(corp);
                if (ev != null && !ev.date().isAfter(t)) continue;                             // 이미 부실
                n++;
                if (ev != null && !ev.date().isAfter(t.plusMonths(horizonMonths))) hits++;
            }
        }
        return new int[]{hits, n};
    }

    /** 이항 비율의 Wilson 95% 구간 — 표본이 작을 때 정규근사보다 덜 틀림 */
    static Interval wilson(int x, int n) {
        if (n == 0) return null;
        double z = 1.959964, p = (double) x / n, z2 = z * z;
        double center = (p + z2 / (2 * n)) / (1 + z2 / n);
        double half = z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / (1 + z2 / n);
        return new Interval(Math.max(0, center - half), Math.min(1, center + half));
    }

    private static int count(List<Episode> eps, Outcome o) {
        return (int) eps.stream().filter(e -> e.outcome() == o).count();
    }

    private static Integer median(List<Integer> sorted) {
        if (sorted.isEmpty()) return null;
        int m = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(m) : (sorted.get(m - 1) + sorted.get(m)) / 2;
    }
}
