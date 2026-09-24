package com.buildrisk.radar.domain.region;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 한 Step 동안 쓰는 지역 해석기 — 출처 코드마다 한 번만 매칭하고 결과를 ref.region_code_map 에 남깁니다.
 * 화면에서 MANUAL 로 고친 매핑이 있으면 그것이 우선입니다.
 */
public class RegionResolver {
    private final RegionRepository repo;
    private final RegionMatcher matcher;
    private final Map<String, Optional<RegionRef>> cache = new HashMap<>();
    private final Map<String, RegionRef> byCd = new HashMap<>();

    public RegionResolver(RegionRepository repo, RegionAliases aliases) {
        this.repo = repo;
        List<RegionRef> refs = repo.refs();
        if (refs.isEmpty()) {
            throw new IllegalStateException("기준 시군구(ref.region)가 비어 있습니다. boundaryLoadJob 을 먼저 실행하세요.");
        }
        refs.forEach(r -> byCd.put(r.regionCd(), r));
        this.matcher = new RegionMatcher(refs, aliases);
    }

    public Optional<RegionRef> resolve(String source, String sourceCode, String sido, List<String> tokens) {
        return cache.computeIfAbsent(source + "|" + sourceCode, k -> {
            Optional<String> manual = repo.manualMapping(source, sourceCode);
            if (manual.isPresent() && byCd.containsKey(manual.get())) return Optional.of(byCd.get(manual.get()));
            RegionMatcher.Match m = matcher.match(source, sido, tokens);
            repo.upsertCodeMap(source, sourceCode, RegionMatcher.sourceName(sido, tokens),
                    m.region() == null ? null : m.region().regionCd(), m.method().name(), m.note());
            return Optional.ofNullable(m.region());
        });
    }
}
