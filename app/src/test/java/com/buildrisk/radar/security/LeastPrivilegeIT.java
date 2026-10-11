package com.buildrisk.radar.security;

import com.buildrisk.radar.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-014 — 런타임 계정은 DML 만: 탈취돼도 스키마 변경·감사 로그 변조·마이그레이션 이력 조작 불가 */
class LeastPrivilegeIT extends IntegrationTest {
    @Autowired
    Flyway flyway;

    private Connection app() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * 런타임 계정 비밀번호는 평문이 아니라 SCRAM 검증값으로 SQL 에 들어감 — afterMigrate 의 DO 블록이 실패하면 PostgreSQL 이
     * 문장 전체를 서버 로그에 남기므로(log_min_error_statement=error) 평문이면 RDS 로그(세 프로젝트 공용)에 비밀번호가 남았음.
     * 저장된 값도 검증값이고, 평문 비밀번호로 그대로 로그인됨 (VERIFICATION 178)
     */
    @Test
    void 앱_계정_비밀번호는_평문이_아니라_SCRAM_검증값으로_마이그레이션에_넘어간다() throws SQLException {
        String placeholder = flyway.getConfiguration().getPlaceholders().get("approlepassword");
        assertThat(placeholder).startsWith("SCRAM-SHA-256$4096:").doesNotContain(APP_ROLE_PASSWORD);
        assertThat(jdbc.queryForObject("SELECT rolpassword FROM pg_authid WHERE rolname = ?", String.class, APP_ROLE))
                .startsWith("SCRAM-SHA-256$4096:");
        try (Connection c = app()) {
            exec(c, "SELECT 1");
        }
    }

    @Test
    void 앱_계정은_데이터를_읽고_쓸_수_있다() throws SQLException {
        company("00000301", "권한건설");
        try (Connection c = app()) {
            exec(c, "SELECT count(*) FROM ref.company");
            exec(c, "UPDATE ref.company SET corp_name = '권한건설2' WHERE corp_code = '00000301'");
            exec(c, "INSERT INTO ops.audit_log (actor, auth_type, action) VALUES ('t', 'ANONYMOUS', 'TEST')");
            exec(c, "SELECT nextval('ops.batch_job_instance_seq')");
            exec(c, "SELECT ST_AsText(ST_Transform(ST_SetSRID(ST_MakePoint(127, 37.5), 4326), 5179))");   // PostGIS
        }
        assertThat(count("SELECT count(*) FROM ref.company WHERE corp_name = '권한건설2'")).isEqualTo(1);
    }

    @Test
    void 앱_계정은_DDL_과_감사_로그_변조가_거부된다() throws SQLException {
        try (Connection c = app()) {
            for (String sql : new String[]{
                    "CREATE TABLE ops.evil (id int)",
                    "CREATE TABLE public.evil (id int)",
                    "DROP TABLE risk.alert",
                    "ALTER TABLE risk.rule ADD COLUMN x int",
                    "TRUNCATE risk.alert",
                    "UPDATE ops.audit_log SET actor = 'x'",
                    "DELETE FROM ops.audit_log",
                    "SELECT * FROM public.flyway_schema_history",
                    "CREATE ROLE evil"}) {
                assertThatThrownBy(() -> exec(c, sql)).as(sql)
                        .hasMessageMatching("(?s).*(permission denied|must be owner).*");
            }
        }
    }
}
