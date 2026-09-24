package com.buildrisk.radar.domain.rule;

import com.buildrisk.radar.domain.rule.RuleModels.RuleDefinition;
import com.buildrisk.radar.domain.rule.RuleModels.TargetType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class RuleRepository {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public RuleRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Version(RuleDefinition def, String changeNote, OffsetDateTime createdAt) {}

    private static final String COLS = "rule_code, version, target_type, name_ko, description, params::text, severity, enabled, change_note, created_at";

    /** 규칙 코드별 최신 버전 */
    public List<Version> latest() {
        return jdbc.sql("SELECT DISTINCT ON (rule_code) " + COLS + " FROM risk.rule ORDER BY rule_code, version DESC")
                .query(this::row).list();
    }

    public List<RuleDefinition> latestEnabled() {
        return latest().stream().map(Version::def).filter(RuleDefinition::enabled).toList();
    }

    public List<Version> history(String code) {
        return jdbc.sql("SELECT " + COLS + " FROM risk.rule WHERE rule_code = :c ORDER BY version DESC")
                .param("c", code).query(this::row).list();
    }

    public Optional<Version> latest(String code) { return history(code).stream().findFirst(); }

    public Optional<RuleDefinition> version(String code, int version) {
        return jdbc.sql("SELECT " + COLS + " FROM risk.rule WHERE rule_code = :c AND version = :v")
                .param("c", code).param("v", version).query(this::row).optional().map(Version::def);
    }

    /** 파라미터·심각도·사용 여부가 바뀌면 새 버전 (FR-502) — 이전 경보는 이전 버전 번호를 유지 */
    public int insertVersion(RuleDefinition base, Map<String, Object> params, String severity, boolean enabled, String note) {
        int next = base.version() + 1;
        jdbc.sql("""
                INSERT INTO risk.rule (rule_code, version, target_type, name_ko, description, params, severity, enabled, change_note)
                VALUES (:c, :v, :t, :n, :d, cast(:p AS jsonb), :s, :e, :note)""")
                .param("c", base.code()).param("v", next).param("t", base.targetType().name())
                .param("n", base.nameKo()).param("d", base.description())
                .param("p", mapper.writeValueAsString(params)).param("s", severity).param("e", enabled)
                .param("note", note).update();
        return next;
    }

    private Version row(ResultSet rs, int i) throws SQLException {
        RuleDefinition d = new RuleDefinition(rs.getString(1), rs.getInt(2), TargetType.valueOf(rs.getString(3)),
                rs.getString(4), rs.getString(5), mapper.readValue(rs.getString(6), MAP), rs.getString(7), rs.getBoolean(8));
        return new Version(d, rs.getString(9), rs.getObject(10, OffsetDateTime.class));
    }
}
