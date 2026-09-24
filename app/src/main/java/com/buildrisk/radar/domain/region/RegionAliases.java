package com.buildrisk.radar.domain.region;

import java.util.List;
import java.util.Map;

/** seed/region_aliases.yml */
public record RegionAliases(Map<String, List<String>> sido, Map<String, String> sgisSido, Map<String, String> names,
                            List<Manual> manual) {
    public record Manual(String source, String name, String regionCd, String note) {}

    public static RegionAliases empty() { return new RegionAliases(Map.of(), Map.of(), Map.of(), List.of()); }
}
