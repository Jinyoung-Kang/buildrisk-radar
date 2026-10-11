package com.buildrisk.radar.common.security;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * afterMigrate.sql 의 런타임 계정 비밀번호(placeholder approlepassword)를 평문 대신 SCRAM 검증값으로 바꿔 넘김 —
 * DO 블록이 실패하면(의도된 기동 중단 경로 포함) PostgreSQL 이 문장 전체를 서버 로그에 남겨, 평문이면 RDS 로그에 비밀번호가 남았음.
 * 앱의 런타임 접속은 그대로 평문 비밀번호로 (VERIFICATION 178, ADR-014)
 */
@Component
public class AppRolePasswordCustomizer implements FlywayConfigurationCustomizer {
    static final String PLACEHOLDER = "approlepassword";
    private static final Logger log = LoggerFactory.getLogger(AppRolePasswordCustomizer.class);

    @Override
    public void customize(FluentConfiguration configuration) {
        String plain = configuration.getPlaceholders().get(PLACEHOLDER);
        if (plain == null || plain.isEmpty()) return;
        ScramVerifier.of(plain).ifPresentOrElse(verifier -> {
            Map<String, String> placeholders = new HashMap<>(configuration.getPlaceholders());
            placeholders.put(PLACEHOLDER, verifier);
            configuration.placeholders(placeholders);
        }, () -> log.warn("DB_APP_PASSWORD 에 ASCII 밖의 문자가 있어 SCRAM 검증값 대신 평문으로 넘깁니다 — 영문 · 숫자 · 기호로 바꾸기를 권합니다"));
    }
}
