package com.buildrisk.radar.api;

import com.buildrisk.radar.api.dto.AlertDtos.AlertDetail;
import com.buildrisk.radar.api.dto.AlertDtos.AlertRow;
import com.buildrisk.radar.api.dto.Page;
import com.buildrisk.radar.common.Disclaimer;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AlertQueryService {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String TARGET_NAME = """
            CASE a.target_type WHEN 'COMPANY' THEN (SELECT corp_name FROM ref.company c WHERE c.corp_code = a.target_key)
                               ELSE (SELECT full_name FROM ref.region r WHERE r.region_cd = a.target_key) END""";
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AlertQueryService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Page<AlertRow> list(String targetType, String severity, String status, OffsetDateTime since, String targetKey,
                               String ruleCode, int page, int size) {
        String where = """
                (cast(:tt AS text) IS NULL OR a.target_type = :tt) AND (cast(:sev AS text) IS NULL OR a.severity = :sev)
                AND (cast(:st AS text) IS NULL OR a.status = ANY(string_to_array(:st, ',')))
                AND (cast(:since AS timestamptz) IS NULL OR a.last_evaluated_at >= :since)
                AND (cast(:tk AS text) IS NULL OR a.target_key = :tk) AND (cast(:rc AS text) IS NULL OR a.rule_code = :rc)""";
        String st = CompanyQueryService.blank(status);
        long total = jdbc.sql("SELECT count(*) FROM risk.alert a WHERE " + where)
                .param("tt", CompanyQueryService.blank(targetType)).param("sev", CompanyQueryService.blank(severity))
                .param("st", st).param("since", since).param("tk", CompanyQueryService.blank(targetKey))
                .param("rc", CompanyQueryService.blank(ruleCode)).query(Long.class).single();
        List<AlertRow> rows = jdbc.sql("SELECT a.alert_id, a.rule_code, a.rule_version, r.name_ko, a.severity, a.target_type, "
                        + "a.target_key, " + TARGET_NAME + ", a.as_of, a.title, a.status, a.close_reason, a.first_seen_at, a.last_evaluated_at "
                        + "FROM risk.alert a JOIN risk.rule r ON r.rule_code = a.rule_code AND r.version = a.rule_version "
                        + "WHERE " + where + " ORDER BY CASE a.status WHEN 'OPEN' THEN 0 WHEN 'ACK' THEN 1 ELSE 2 END, "
                        + "CASE a.severity WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, a.as_of DESC, a.alert_id DESC "
                        + "LIMIT :lim OFFSET :off")
                .param("tt", CompanyQueryService.blank(targetType)).param("sev", CompanyQueryService.blank(severity))
                .param("st", st).param("since", since).param("tk", CompanyQueryService.blank(targetKey))
                .param("rc", CompanyQueryService.blank(ruleCode)).param("lim", size).param("off", (long) page * size)
                .query((rs, i) -> new AlertRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getString(12), rs.getObject(13, OffsetDateTime.class),
                        rs.getObject(14, OffsetDateTime.class))).list();
        return new Page<>(rows, total, page, size);
    }

    public AlertDetail detail(long id) {
        return jdbc.sql("SELECT a.alert_id, a.rule_code, a.rule_version, r.name_ko, r.description, a.severity, a.target_type, "
                        + "a.target_key, " + TARGET_NAME + ", a.as_of, a.title, a.message, a.status, a.close_reason, "
                        + "a.evidence::text, a.first_seen_at, a.last_evaluated_at, a.acked_at, a.closed_at, a.calc_run_id::text, a.acked_by "
                        + "FROM risk.alert a JOIN risk.rule r ON r.rule_code = a.rule_code AND r.version = a.rule_version "
                        + "WHERE a.alert_id = :id")
                .param("id", id)
                .query((rs, i) -> new AlertDetail(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14),
                        mapper.readValue(rs.getString(15), MAP), rs.getObject(16, OffsetDateTime.class),
                        rs.getObject(17, OffsetDateTime.class), rs.getObject(18, OffsetDateTime.class), rs.getString(21),
                        rs.getObject(19, OffsetDateTime.class), rs.getString(20), Disclaimer.TEXT))
                .optional().orElseThrow(() -> ApiException.notFound(ErrorCode.ALERT_NOT_FOUND, "경보 " + id));
    }

    /** FR-504 — 사람이 바꿀 수 있는 상태는 ACK(확인)와 OPEN(확인 취소). CLOSED 는 규칙 평가가 정합니다. */
    public AlertDetail changeStatus(long id, String status, String actor) {
        if (status == null || !Set.of("ACK", "OPEN").contains(status)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "status 는 ACK 또는 OPEN 만 바꿀 수 있습니다.");
        }
        AlertDetail cur = detail(id);
        if ("CLOSED".equals(cur.status())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "이미 닫힌 경보입니다 (" + cur.closeReason() + ").");
        }
        jdbc.sql("""
                UPDATE risk.alert SET status = :s, acked_at = CASE WHEN :s = 'ACK' THEN now() END,
                       acked_by = CASE WHEN :s = 'ACK' THEN :actor END
                 WHERE alert_id = :id""")
                .param("s", status).param("actor", actor).param("id", id).update();
        return detail(id);
    }
}
