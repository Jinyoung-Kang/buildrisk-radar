package com.buildrisk.radar.security;

import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-014 — 런타임 계정은 DML 만: 탈취돼도 스키마 변경·감사 로그 변조·마이그레이션 이력 조작 불가 */
class LeastPrivilegeIT extends IntegrationTest {

    private Connection app() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement()) {
            st.execute(sql);
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
