package com.buildrisk.radar.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class AlertDtos {
    private AlertDtos() {}

    public record AlertRow(long alertId, String ruleCode, int ruleVersion, String ruleName, String severity,
                           String targetType, String targetKey, String targetName, String asOf, String title,
                           String status, String closeReason, OffsetDateTime firstSeenAt, OffsetDateTime lastEvaluatedAt) {}

    public record AlertDetail(long alertId, String ruleCode, int ruleVersion, String ruleName, String ruleDescription,
                              String severity, String targetType, String targetKey, String targetName, String asOf,
                              String title, String message, String status, String closeReason, Map<String, Object> evidence,
                              OffsetDateTime firstSeenAt, OffsetDateTime lastEvaluatedAt, OffsetDateTime ackedAt,
                              String ackedBy, OffsetDateTime closedAt, String calcRunId, String disclaimer) {}

    public record StatusChange(String status) {}

    public record RuleView(String ruleCode, int version, String targetType, String nameKo, String description,
                           Map<String, Object> params, String severity, boolean enabled, String condition,
                           String changeNote, String createdBy, OffsetDateTime createdAt, long openAlerts) {}

    public record RuleHistory(String ruleCode, List<RuleView> versions) {}

    public record RuleUpdate(Map<String, Object> params, String severity, Boolean enabled, String changeNote) {}
}
