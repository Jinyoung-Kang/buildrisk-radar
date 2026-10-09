package com.buildrisk.radar.metric.domain;

import java.math.BigDecimal;

/** 지역 지표 계산 입력: (stat_code, region_cd, period, value) */
public record StatPoint(String statCode, String agg, String regionCd, String period, BigDecimal value) {}
