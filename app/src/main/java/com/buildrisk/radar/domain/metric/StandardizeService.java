package com.buildrisk.radar.domain.metric;

import com.buildrisk.radar.domain.account.AccountModels.StdValue;
import com.buildrisk.radar.domain.account.AccountStandardizer;
import com.buildrisk.radar.domain.account.FsRepository;
import com.buildrisk.radar.domain.account.FsRepository.Report;
import com.buildrisk.radar.domain.account.FsRepository.StdRow;
import com.buildrisk.radar.domain.account.QuarterDeriver;
import com.buildrisk.radar.domain.metric.CompanyMetricCalculator.PeriodFacts;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** fs_raw → fs_std (POINT·CUM + 차분 QTR) 와 fs_std → 기업 지표 입력(PeriodFacts) */
@Service
public class StandardizeService {
    private final FsRepository fs;

    public StandardizeService(FsRepository fs) { this.fs = fs; }

    public AccountStandardizer standardizer() { return new AccountStandardizer(fs.stdAccounts(), fs.mapRules()); }

    public List<StdRow> standardize(String corpCode, AccountStandardizer std) {
        List<StdRow> rows = new ArrayList<>();
        // std_code → fs_div → period → 누적
        Map<String, Map<String, Map<String, QuarterDeriver.Cum>>> cum = new HashMap<>();
        Map<String, Report> reportOf = new HashMap<>();
        for (Report r : fs.latestReports(corpCode)) {
            reportOf.put(r.periodKey() + "|" + r.fsDiv(), r);
            for (StdValue v : std.standardize(r.lines()).values()) {
                rows.add(new StdRow(corpCode, r.periodKey(), r.fsDiv(), v.stdCode(), v.basis().name(), v.amount(),
                        v.sourceAccount(), v.sourceSjDiv(), r.rceptNo(), r.reprtCode()));
                if (v.basis() == com.buildrisk.radar.domain.account.AccountModels.Basis.CUM) {
                    cum.computeIfAbsent(v.stdCode(), k -> new HashMap<>()).computeIfAbsent(r.fsDiv(), k -> new TreeMap<>())
                            .put(r.periodKey(), new QuarterDeriver.Cum(v.amount(), r.fsDiv()));
                }
            }
        }
        cum.forEach((stdCode, byFs) -> byFs.forEach((fsDiv, series) -> QuarterDeriver.derive(series).forEach((pk, amt) -> {
            Report r = reportOf.get(pk + "|" + fsDiv);
            rows.add(new StdRow(corpCode, pk, fsDiv, stdCode, "QTR", amt, "누적 차분", null,
                    r == null ? null : r.rceptNo(), r == null ? null : r.reprtCode()));
        })));
        return rows;
    }

    /** 기간마다 연결(CFS) 우선, 없으면 별도(OFS) — AUTO */
    public Map<String, PeriodFacts> facts(List<StdRow> rows) {
        Map<String, Map<String, List<StdRow>>> byPeriodFs = new TreeMap<>();
        for (StdRow r : rows) {
            byPeriodFs.computeIfAbsent(r.periodKey(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(r.fsDiv(), k -> new ArrayList<>()).add(r);
        }
        Map<String, PeriodFacts> out = new TreeMap<>();
        byPeriodFs.forEach((pk, byFs) -> {
            String fsDiv = byFs.containsKey("CFS") ? "CFS" : "OFS";
            Map<String, BigDecimal> point = new HashMap<>(), cum = new HashMap<>(), qtr = new HashMap<>();
            Map<String, String> sources = new HashMap<>();
            String rcept = null, reprt = null;
            for (StdRow r : byFs.get(fsDiv)) {
                if (!"QTR".equals(r.basis()) && r.sourceAccount() != null) {
                    sources.put(r.stdCode(), (r.sourceSjDiv() == null ? "" : r.sourceSjDiv() + ":") + r.sourceAccount());
                }
                switch (r.basis()) {
                    case "POINT" -> point.put(r.stdCode(), r.amount());
                    case "CUM" -> cum.put(r.stdCode(), r.amount());
                    default -> qtr.put(r.stdCode(), r.amount());
                }
                if (r.rceptNo() != null && !"QTR".equals(r.basis())) {
                    rcept = r.rceptNo();
                    reprt = r.reprtCode();
                }
            }
            out.put(pk, new PeriodFacts(pk, fsDiv, rcept, reprt, point, cum, qtr, sources));
        });
        return out;
    }
}
