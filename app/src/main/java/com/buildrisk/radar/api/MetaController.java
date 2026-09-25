package com.buildrisk.radar.api;

import com.buildrisk.radar.common.Disclaimer;
import com.buildrisk.radar.common.seed.SeedCatalog;
import com.buildrisk.radar.domain.metric.MetricCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.buildrisk.radar.common.role.ApiRole;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@ApiRole
@RequestMapping("/api/v1")
@Tag(name = "메타", description = "대시보드 · 지표 정의 · 출처 · 고지")
public class MetaController {
    private final DashboardService dashboard;
    private final SeedCatalog seed;

    public MetaController(DashboardService dashboard, SeedCatalog seed) {
        this.dashboard = dashboard;
        this.seed = seed;
    }

    @GetMapping("/dashboard")
    @Operation(summary = "대시보드 요약")
    public Map<String, Object> dashboard() { return dashboard.summary(); }

    @GetMapping("/meta")
    @Operation(summary = "지표 정의서 · 이벤트 유형 · 출처 · 고지")
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metrics", MetricCatalog.all());
        m.put("eventTypes", seed.eventClassifier().types());
        m.put("sources", List.of(
                Map.of("code", "DART", "name", "금융감독원 Open DART", "use", "고유번호 · 기업개황 · 단일회사 전체 재무제표 · 공시검색",
                        "url", "https://opendart.fss.or.kr"),
                Map.of("code", "KOSIS", "name", "KOSIS 공유서비스", "use", "시·군·구별 미분양현황 (116/DT_MLTM_2082)",
                        "url", "https://kosis.kr/openapi"),
                Map.of("code", "RONE", "name", "한국부동산원 R-ONE", "use", "(월) 아파트 매매·전세 가격지수 (A_2024_00045 · A_2024_00050)",
                        "url", "https://www.reb.or.kr/r-one"),
                Map.of("code", "SGIS", "name", "통계청 SGIS", "use", "총조사 주요지표 총가구", "url", "https://sgis.mods.go.kr"),
                Map.of("code", "VWORLD", "name", "V-World 데이터 API", "use", "시군구 경계 (LT_C_ADSIGG_INFO)", "url", "https://www.vworld.kr"),
                Map.of("code", "KAKAO", "name", "카카오 지도 JavaScript SDK", "use", "배경 지도", "url", "https://apis.map.kakao.com")));
        m.put("disclaimer", Disclaimer.TEXT);
        return m;
    }
}
