package com.buildrisk.radar.domain.backtest;

import com.buildrisk.radar.domain.market.StockRepository.Bar;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 경보 사건 연구 (ADR-017) — 경보가 '공개된 날'(근거 공시의 접수일) 다음 거래일 종가에 들어가 h 거래일 뒤 종가까지의 수익률을
 * 같은 기간 나머지 유니버스(건설사) 동일가중 평균과 비교한 초과수익률.
 * <ul>
 *   <li>원천 종가는 수정주가가 아님 → 구간 안에서 상장주식수가 tolerance 이상 바뀐 종목(사건·비교군 모두)은 제외</li>
 *   <li>같은 규칙·종목의 사건이 창(h)과 겹치면 뒤의 것은 제외 (같은 신호 중복 집계 방지)</li>
 *   <li>h 거래일이 아직 지나지 않은 사건은 제외</li>
 * </ul>
 */
public final class EventStudy {
    public static final double SHARE_TOLERANCE = 0.02;
    public static final int MIN_BENCH = 5;
    private static final MathContext MC = MathContext.DECIMAL64;

    public record Event(long id, String ruleCode, String stockCode, LocalDate eventDate) {}

    public enum Excluded { NO_PRICE, HORIZON_NOT_ELAPSED, SHARE_CHANGE, OVERLAP, NO_BENCHMARK }

    public record Outcome(Event event, LocalDate entryDate, LocalDate exitDate, Double ret, Double bench, Double excess,
                          Integer benchSize, Excluded excluded) {}

    public record Stats(int events, int used, Map<Excluded, Integer> excluded, Double meanExcess, Double medianExcess,
                        Double negativeShare, Double meanReturn, Double tStat) {}

    private final Map<String, List<Bar>> bars = new HashMap<>();
    private final Map<String, Map<LocalDate, Integer>> index = new HashMap<>();

    public EventStudy(List<Bar> all) {
        for (Bar b : all) bars.computeIfAbsent(b.stockCode(), k -> new ArrayList<>()).add(b);
        bars.values().forEach(l -> l.sort(Comparator.comparing(Bar::basDt)));
        bars.forEach((code, l) -> {
            Map<LocalDate, Integer> m = new HashMap<>();
            for (int i = 0; i < l.size(); i++) m.put(l.get(i).basDt(), i);
            index.put(code, m);
        });
    }

    public List<Outcome> run(List<Event> events, int h) {
        List<Outcome> out = new ArrayList<>();
        Map<String, Integer> lastEntry = new HashMap<>();   // rule|stock → 마지막으로 쓴 사건의 진입 인덱스
        List<Event> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(Event::eventDate).thenComparing(Event::id));
        for (Event e : sorted) out.add(one(e, h, lastEntry));
        return out;
    }

    private Outcome one(Event e, int h, Map<String, Integer> lastEntry) {
        List<Bar> s = bars.get(e.stockCode());
        if (s == null || s.isEmpty()) return new Outcome(e, null, null, null, null, null, null, Excluded.NO_PRICE);
        int entry = firstAfter(s, e.eventDate());
        if (entry < 0 || s.get(0).basDt().isAfter(e.eventDate())) {
            return new Outcome(e, null, null, null, null, null, null, entry < 0 ? Excluded.HORIZON_NOT_ELAPSED : Excluded.NO_PRICE);
        }
        if (entry + h >= s.size()) {
            return new Outcome(e, s.get(entry).basDt(), null, null, null, null, null, Excluded.HORIZON_NOT_ELAPSED);
        }
        LocalDate in = s.get(entry).basDt(), outDt = s.get(entry + h).basDt();
        String key = e.ruleCode() + "|" + e.stockCode();
        Integer prev = lastEntry.get(key);
        if (prev != null && entry < prev + h) return new Outcome(e, in, outDt, null, null, null, null, Excluded.OVERLAP);
        if (shareChanged(s, entry, entry + h)) return new Outcome(e, in, outDt, null, null, null, null, Excluded.SHARE_CHANGE);
        double r = ret(s, entry, entry + h);
        List<Double> peers = new ArrayList<>();
        bars.forEach((code, l) -> {
            if (code.equals(e.stockCode())) return;
            Integer a = index.get(code).get(in), b = index.get(code).get(outDt);
            if (a == null || b == null || shareChanged(l, a, b)) return;
            peers.add(ret(l, a, b));
        });
        if (peers.size() < MIN_BENCH) return new Outcome(e, in, outDt, r, null, null, peers.size(), Excluded.NO_BENCHMARK);
        double bench = peers.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        lastEntry.put(key, entry);
        return new Outcome(e, in, outDt, r, bench, r - bench, peers.size(), null);
    }

    /** 비교 기준: 모든 종목을 h 거래일 간격으로 겹치지 않게 자른 창의 초과수익률 (신호 없는 평소 분포) */
    public List<Double> baseline(int h) {
        List<Double> out = new ArrayList<>();
        bars.forEach((code, s) -> {
            for (int i = 0; i + h < s.size(); i += h) {
                LocalDate in = s.get(i).basDt(), outDt = s.get(i + h).basDt();
                if (shareChanged(s, i, i + h)) continue;
                List<Double> peers = new ArrayList<>();
                bars.forEach((c2, l) -> {
                    if (c2.equals(code)) return;
                    Integer a = index.get(c2).get(in), b = index.get(c2).get(outDt);
                    if (a == null || b == null || shareChanged(l, a, b)) return;
                    peers.add(ret(l, a, b));
                });
                if (peers.size() < MIN_BENCH) continue;
                out.add(ret(s, i, i + h) - peers.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
            }
        });
        return out;
    }

    public static Stats stats(List<Outcome> outcomes) {
        Map<Excluded, Integer> ex = new LinkedHashMap<>();
        List<Double> xs = new ArrayList<>(), rs = new ArrayList<>();
        for (Outcome o : outcomes) {
            if (o.excluded() != null) ex.merge(o.excluded(), 1, Integer::sum);
            else {
                xs.add(o.excess());
                rs.add(o.ret());
            }
        }
        return summarize(outcomes.size(), xs, rs, ex);
    }

    public static Stats summarize(int events, List<Double> xs, List<Double> rs, Map<Excluded, Integer> ex) {
        if (xs.isEmpty()) return new Stats(events, 0, ex, null, null, null, null, null);
        double mean = xs.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        List<Double> sorted = new ArrayList<>(xs);
        Collections.sort(sorted);
        int n = sorted.size();
        double median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        double neg = xs.stream().filter(x -> x < 0).count() / (double) n;
        Double t = null;
        if (n >= 2) {
            double var = xs.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / (n - 1);
            t = var == 0 ? null : mean / Math.sqrt(var / n);
        }
        Double meanRet = rs == null || rs.isEmpty() ? null : rs.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        return new Stats(events, n, ex, mean, median, neg, meanRet, t);
    }

    private static int firstAfter(List<Bar> s, LocalDate d) {
        for (int i = 0; i < s.size(); i++) if (s.get(i).basDt().isAfter(d)) return i;
        return -1;
    }

    private static double ret(List<Bar> s, int a, int b) {
        return s.get(b).clpr().divide(s.get(a).clpr(), MC).subtract(BigDecimal.ONE).doubleValue();
    }

    private static boolean shareChanged(List<Bar> s, int a, int b) {
        Long base = s.get(a).lstgStCnt();
        if (base == null || base == 0) return false;
        for (int i = a + 1; i <= b; i++) {
            Long v = s.get(i).lstgStCnt();
            if (v != null && Math.abs(v - base) / (double) base > SHARE_TOLERANCE) return true;
        }
        return false;
    }
}
