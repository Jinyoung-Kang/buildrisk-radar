package com.buildrisk.radar.company.app;

import com.buildrisk.radar.company.data.FsRepository;
import com.buildrisk.radar.company.domain.AccountModels.StdValue;
import com.buildrisk.radar.company.domain.AccountModels;
import com.buildrisk.radar.company.domain.AccountStandardizer;
import com.buildrisk.radar.company.domain.FsModels.Report;
import com.buildrisk.radar.company.domain.FsModels.StdRow;
import com.buildrisk.radar.company.domain.QuarterDeriver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** fs_raw → fs_std (POINT·CUM + 차분 QTR). 기업 지표 입력(PeriodFacts)은 CompanyMetricCalculator.facts */
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
                if (v.basis() == AccountModels.Basis.CUM) {
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
}
