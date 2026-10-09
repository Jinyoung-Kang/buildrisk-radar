package com.buildrisk.radar.metric.domain;

import java.math.BigDecimal;
import java.util.Map;

/** 지표 한 점 (company_metric · region_metric) */
public record MetricPoint(String period, BigDecimal value, String status, Map<String, Object> components) {
    public boolean ok() { return value != null && "OK".equals(status); }
}
