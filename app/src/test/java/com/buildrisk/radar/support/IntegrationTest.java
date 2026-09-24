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
    public static final String ADMIN = "test-admin-token";
    static final PostgreSQLContainer PG = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("test.pg.image", "imresamu/postgis:16-3.6-bookworm"))
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
        r.add("buildrisk.admin-token", () -> ADMIN);
        r.add("buildrisk.dart.base-url", WM::baseUrl);
        r.add("buildrisk.dart.api-key", () -> "test-dart-key");
        r.add("buildrisk.dart.min-interval-ms", () -> 0);
        r.add("buildrisk.dart.from-year", () -> 2024);
        r.add("buildrisk.kosis.base-url", WM::baseUrl);
        r.add("buildrisk.rone.base-url", WM::baseUrl);
        r.add("buildrisk.sgis.base-url", WM::baseUrl);
        r.add("buildrisk.vworld.base-url", WM::baseUrl);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    /** 테스트 사이 데이터 초기화 (seed 테이블·배치 메타는 유지) */
    @BeforeEach
    void cleanData() {
        WM.resetAll();
        jdbc.execute("""
                TRUNCATE risk.alert, risk.company_metric, mkt.region_metric, mkt.region_stat, dart.fs_std, dart.fs_raw,
                  dart.fs_fetch, dart.disclosure, ref.universe_history, ops.skip_log, ops.api_quota, ops.collect_run CASCADE""");
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
