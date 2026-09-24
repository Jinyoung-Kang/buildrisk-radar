package com.buildrisk.radar.adapters.common;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 외부 API 일일 호출 수 (ops.api_quota, KST 기준). 청크 트랜잭션이 롤백돼도 실제 호출은 일어났으므로
 * 별도 트랜잭션(REQUIRES_NEW)으로 셉니다 — NFR-03.
 */
@Component
public class ApiQuotaService {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcClient jdbc;
    private final TransactionTemplate requiresNew;

    public ApiQuotaService(JdbcClient jdbc, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(tm);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 호출 1건을 기록하고 오늘 누적 호출 수를 돌려줍니다. */
    public int increment(String provider) {
        Integer v = requiresNew.execute(s -> jdbc.sql("""
                        INSERT INTO ops.api_quota (provider, day, calls) VALUES (:p, :d, 1)
                        ON CONFLICT (provider, day) DO UPDATE SET calls = ops.api_quota.calls + 1
                        RETURNING calls""")
                .param("p", provider).param("d", today()).query(Integer.class).single());
        return v == null ? 0 : v;
    }

    public int used(String provider) {
        return jdbc.sql("SELECT coalesce(max(calls), 0) FROM ops.api_quota WHERE provider = :p AND day = :d")
                .param("p", provider).param("d", today()).query(Integer.class).single();
    }

    public static LocalDate today() { return LocalDate.now(KST); }
}
