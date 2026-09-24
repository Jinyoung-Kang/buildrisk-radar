package com.buildrisk.radar.batch.support;

import java.util.List;
import java.util.Optional;

/** 배치 Job 목록 (5장) — 실행 순서대로. 배치 모니터·CLI·스케줄러가 같은 정의를 씁니다. */
public final class JobCatalog {
    public record Def(String name, String title, String source, String schedule, String requirement, String envKey) {}

    public static final List<Def> JOBS = List.of(
            new Def("corpCodeSyncJob", "DART 고유번호 동기화", "DART", "매주 월 02:00", "FR-101", "DART_API_KEY"),
            new Def("companyProfileJob", "상장사 기업개황 · 유니버스", "DART", "매주 월 02:30", "FR-102~103", "DART_API_KEY"),
            new Def("financialStatementJob", "재무제표 수집 (미수집분)", "DART", "매일 03:00", "FR-201~203", "DART_API_KEY"),
            new Def("disclosureSyncJob", "공시 수집 · 이벤트 분류", "DART", "매일 03:30", "FR-301~302", "DART_API_KEY"),
            new Def("boundaryLoadJob", "시군구 경계 적재", "V-World (없으면 SGIS)", "최초 1회", "FR-404", null),
            new Def("sgisHouseholdJob", "시군구 총가구", "SGIS", "매년 1월 15일", "FR-403", "SGIS_CONSUMER_KEY"),
            new Def("kosisUnsoldJob", "시군구 미분양 (월)", "KOSIS", "매월 20일 04:00", "FR-401", "KOSIS_API_KEY"),
            new Def("roneIndexJob", "아파트 매매·전세 가격지수 (월)", "R-ONE", "매주 금 04:10", "FR-402", "REB_API_KEY"),
            new Def("standardizeMetricJob", "표준화 · 기업/지역 지표", "내부", "매일 05:00", "FR-204~205, 501", null),
            new Def("ruleEvalJob", "규칙 평가 · 경보", "내부", "지표 Job 완료 후", "FR-502~504", null));

    private JobCatalog() {}

    public static Optional<Def> find(String name) { return JOBS.stream().filter(j -> j.name().equals(name)).findFirst(); }
}
