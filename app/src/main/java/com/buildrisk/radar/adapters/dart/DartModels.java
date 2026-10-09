package com.buildrisk.radar.adapters.dart;

import com.buildrisk.radar.company.domain.FsModels.FsRow;
import com.buildrisk.radar.filing.domain.FilingModels.Disclosure;

import java.util.List;

public final class DartModels {
    private DartModels() {}

    /** 재무제표 응답 — noData 면 rows 는 비어 있음 (013) */
    public record FsResponse(String fsDiv, boolean noData, List<FsRow> rows) {}

    public record DisclosurePage(int pageNo, int totalPage, int totalCount, List<Disclosure> items) {}
}
