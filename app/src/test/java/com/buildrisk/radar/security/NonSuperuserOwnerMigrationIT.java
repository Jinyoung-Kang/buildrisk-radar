package com.buildrisk.radar.security;

import com.buildrisk.radar.common.security.ScramVerifier;
import com.buildrisk.radar.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * RDS 처럼 소유자가 superuser 가 아닐 때(CREATEROLE 만) — Flyway 는 기동할 때마다 afterMigrate 로 런타임 계정을 다시 맞춘다.
 * PG16+ 는 superuser 가 아니면 ALTER ROLE 에 NOSUPERUSER · NOCREATEDB · NOREPLICATION 을 '적는 것'만으로 거절하므로
 * 첫 기동(CREATE ROLE)은 되고 두 번째 기동부터 api 가 뜨지 않았다 (VERIFICATION 169, ADR-031).
 */
class NonSuperuserOwnerMigrationIT extends IntegrationTest {
    private static final String DB = "br_rds_like";
    private static final String OWNER = "br_rds_owner";
    private static final String OWNER_PASSWORD = "test-rds-owner-password";
    private static final String APP = "br_rds_app";
    private static final String APP_PASSWORD = "test-rds-app-password";

    @AfterEach
    void dropRdsLikeDatabase() {
        jdbc.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        jdbc.execute("DROP ROLE IF EXISTS " + APP);
        jdbc.execute("DROP ROLE IF EXISTS " + OWNER);
    }

    @Test
    void 비_superuser_소유자로_다시_기동해도_마이그레이션이_실패하지_않는다() throws SQLException {
        dropRdsLikeDatabase();
        jdbc.execute("CREATE ROLE " + OWNER + " LOGIN CREATEROLE PASSWORD '" + OWNER_PASSWORD + "'");
        jdbc.execute("CREATE DATABASE " + DB + " OWNER " + OWNER);
        try (Connection su = superuserConnection(DB); var st = su.createStatement()) {
            st.execute("CREATE EXTENSION postgis");                       // RDS 에서는 마스터가 미리 설치
        }
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(DB), OWNER, OWNER_PASSWORD)
                .schemas("public")
                .locations("classpath:db/migration")
                // 운영과 같이 SCRAM 검증값으로 — CREATEROLE 만 가진 소유자도 미리 계산한 검증값을 설정할 수 있음 (VERIFICATION 178)
                .placeholders(Map.of("approle", APP, "approlepassword", ScramVerifier.of(APP_PASSWORD).orElseThrow()))
                .load();

        flyway.migrate();                                                  // 첫 기동: CREATE ROLE
        assertThatCode(flyway::migrate).doesNotThrowAnyException();       // 두 번째 기동: 이미 있는 역할을 다시 맞춤

        assertThat(jdbc.queryForObject(
                "SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname = ?",
                Boolean.class, APP)).isFalse();
        try (Connection app = DriverManager.getConnection(jdbcUrl(DB), APP, APP_PASSWORD); var st = app.createStatement()) {
            st.execute("SELECT count(*) FROM ref.company");                // 권한도 그대로
        }

        // api 와 단독 batch 가 동시에 기동해도 afterMigrate 가 서로 막지 않음 — Flyway 는 콜백을 잠금 밖에서 돌려, 같은 역할 · 권한을
        // 동시에 고치다 'tuple concurrently updated' 로 진 쪽이 뜨지 않았음(PG18 실험 120회 중 50회) (VERIFICATION 188)
        var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 8; round++) {
                var runs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 3; i++) runs.add(pool.submit(() -> { flyway.migrate(); return null; }));
                for (var f : runs) {
                    try {
                        f.get();
                    } catch (Exception e) {
                        throw new AssertionError("동시 migrate " + (round + 1) + "회차 실패: " + e.getCause(), e);
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
