package com.buildrisk.radar.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * 통합 테스트 공통 (NFR-09): PostGIS·Redis 는 Testcontainers, 외부 API 는 WireMock.
 * 컨테이너는 JVM 당 한 번만 띄우고 Spring 컨텍스트도 공유합니다.
 */
@SpringBootTest
public abstract class IntegrationTest {
    public static final String ADMIN = "test-admin-token-for-integration-tests";
    public static final String APP_ROLE = "buildrisk_app";
    public static final String APP_ROLE_PASSWORD = "test-app-role-password";
    public static final String ADMIN_PASSWORD = "test-admin-password";
    public static final String ANALYST_PASSWORD = "test-analyst-password";
    public static final String NOTIFY_KEY = "test-notify-secret-key-0123456789";
    /**
     * 운영(AWS RDS)과 같은 PostgreSQL 18 + PostGIS 3.6 — 16 에서는 GEOS 3.12+ 경로(ST_CoverageSimplify)가 돌지 않았음.
     * arm64 · amd64 둘 다 있고 지금 갱신되는 태그. 다른 버전으로: ./gradlew test -Dtest.pg.image=…
     */
    static final PostgreSQLContainer PG = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("test.pg.image", "imresamu/postgis:18-3.6-alpine"))
                    .asCompatibleSubstituteFor("postgres"));
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    protected static final WireMockServer WM = new WireMockServer(options().dynamicPort());

    static {
        PG.start();
        REDIS.start();
        WM.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("spring.flyway.placeholders.approle", () -> APP_ROLE);
        r.add("spring.flyway.placeholders.approlepassword", () -> APP_ROLE_PASSWORD);
        r.add("buildrisk.admin-token", () -> ADMIN);
        r.add("buildrisk.security.admin-password", () -> ADMIN_PASSWORD);
        r.add("buildrisk.security.analyst-password", () -> ANALYST_PASSWORD);
        r.add("buildrisk.security.rate-limit-per-minute", () -> 0);
        r.add("buildrisk.security.export-limit-per-minute", () -> 0);
        r.add("buildrisk.dart.base-url", WM::baseUrl);
        r.add("buildrisk.dart.api-key", () -> "test-dart-key");
        r.add("buildrisk.dart.min-interval-ms", () -> 0);
        r.add("buildrisk.dart.from-year", () -> 2024);
        r.add("buildrisk.kosis.base-url", WM::baseUrl);
        r.add("buildrisk.rone.base-url", WM::baseUrl);
        r.add("buildrisk.sgis.base-url", WM::baseUrl);
        r.add("buildrisk.vworld.base-url", WM::baseUrl);
        r.add("buildrisk.data-go-kr.base-url", WM::baseUrl);
        r.add("buildrisk.data-go-kr.service-key", () -> "test-data-key");
        r.add("buildrisk.data-go-kr.min-interval-ms", () -> 0);
        r.add("buildrisk.notification.secret-key", () -> NOTIFY_KEY);
        r.add("buildrisk.notification.allow-insecure-targets", () -> true);      // WireMock(http · 127.0.0.1) — 운영은 false
    }

    @Autowired
    protected JdbcTemplate jdbc;

    protected static String jdbcUrl() { return PG.getJdbcUrl(); }

    /** 같은 컨테이너의 다른 데이터베이스 주소 */
    protected static String jdbcUrl(String database) {
        return PG.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
    }

    /** 컨테이너 superuser 로 다른 데이터베이스에 접속 — RDS 마스터만 할 수 있는 일(확장 설치)을 흉내 낼 때 */
    protected static java.sql.Connection superuserConnection(String database) throws java.sql.SQLException {
        return java.sql.DriverManager.getConnection(jdbcUrl(database), PG.getUsername(), PG.getPassword());
    }

    /** 테스트 사이 데이터 초기화 (seed 테이블·배치 메타는 유지) */
    @BeforeEach
    void cleanData() {
        WM.resetAll();
        jdbc.execute("""
                TRUNCATE risk.alert, risk.company_metric, mkt.region_metric, mkt.region_stat, dart.fs_std, dart.fs_raw,
                  dart.fs_fetch, dart.disclosure, dart.pf_report, ref.universe_history, ops.skip_log, ops.api_quota, ops.collect_run CASCADE""");
        jdbc.execute("TRUNCATE notify.outbox, notify.inbox, notify.delivery, notify.subscription, notify.webhook, notify.watch CASCADE");
        jdbc.execute("DELETE FROM ops.calc_run");
        jdbc.execute("DELETE FROM ref.company");
        jdbc.execute("DELETE FROM risk.rule WHERE version > 1");
        jdbc.execute("UPDATE risk.rule SET enabled = true");
    }

    protected void company(String corpCode, String name) {
        jdbc.update("""
                INSERT INTO ref.company (corp_code, corp_name, stock_code, corp_cls, induty_code, is_target, target_reason)
                VALUES (?, ?, ?, 'Y', '41221', true, 'INDUTY:41')""", corpCode, name, corpCode.substring(2));
    }

    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
