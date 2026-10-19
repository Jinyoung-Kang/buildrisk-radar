package com.buildrisk.radar.insight.app;

import com.buildrisk.radar.adapters.seed.SeedCatalog;
import com.buildrisk.radar.common.Disclaimer;
import com.buildrisk.radar.metric.domain.MetricCatalog;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 지표 정의서 · 공시 유형(seed 사전) · 데이터 출처 · 고지 */
@Service
public class MetaService {
    private final SeedCatalog seed;

    public MetaService(SeedCatalog seed) { this.seed = seed; }

    public Map<String, Object> describe() {
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
                Map.of("code", "APPLYHOME", "name", "한국부동산원 청약홈 분양정보 조회 서비스",
                        "use", "APT 분양 공고 · 주택형 · 잔여세대 공고 (공고 세대 · 입주예정월 · 시공사)", "url", "https://www.applyhome.co.kr"),
                Map.of("code", "VWORLD", "name", "V-World 데이터 API", "use", "시군구 경계 (LT_C_ADSIGG_INFO)", "url", "https://www.vworld.kr"),
                Map.of("code", "KAKAO", "name", "카카오 지도 JavaScript SDK", "use", "배경 지도", "url", "https://apis.map.kakao.com")));
        m.put("disclaimer", Disclaimer.TEXT);
        return m;
    }
}
