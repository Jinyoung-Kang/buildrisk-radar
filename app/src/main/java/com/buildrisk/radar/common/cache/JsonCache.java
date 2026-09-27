package com.buildrisk.radar.common.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Redis 조회 캐시. 키 앞에 '세대(generation)' 번호를 붙여 계산 run 이 끝날 때 INCR 한 번으로
 * 전체를 무효화합니다 (calc_run 단위 무효화). Redis 가 없으면 캐시 없이 동작합니다.
 */
@Component
public class JsonCache {
    private static final Logger log = LoggerFactory.getLogger(JsonCache.class);
    private static final String GEN_KEY = "br:gen";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;
    private volatile long lastWarn;

    /** 적중률은 Prometheus 의 buildrisk_cache_requests_total{result} 로 — 캐시가 실제로 DB 부하를 덜어 주는지 확인 (ADR-022) */
    public JsonCache(StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry registry) {
        this.redis = redis;
        this.mapper = mapper;
        this.hits = counter(registry, "hit");
        this.misses = counter(registry, "miss");
        this.errors = counter(registry, "error");
    }

    private static Counter counter(MeterRegistry registry, String result) {
        return Counter.builder("buildrisk.cache.requests").tag("cache", "redis").tag("result", result)
                .description("조회 캐시 요청 (hit · miss · error = Redis 불가로 캐시 없이 조회)").register(registry);
    }

    public <T> T get(String key, Duration ttl, Class<T> type, Supplier<T> loader) {
        return get(key, ttl, mapper.getTypeFactory().constructType(type), loader);
    }

    /** 제네릭 응답(Page&lt;CompanyRow&gt; 등)용 — Class 로 읽으면 항목이 Map 으로 풀려 숫자 표현이 달라질 수 있음 */
    public <T> T get(String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        return get(key, ttl, mapper.getTypeFactory().constructType(type), loader);
    }

    private <T> T get(String key, Duration ttl, JavaType type, Supplier<T> loader) {
        String full;
        try {
            String gen = redis.opsForValue().get(GEN_KEY);
            full = "br:" + (gen == null ? "0" : gen) + ":" + key;
            String hit = redis.opsForValue().get(full);
            if (hit != null) {
                hits.increment();
                return mapper.readValue(hit, type);
            }
        } catch (RuntimeException e) {
            errors.increment();
            warn(e);
            return loader.get();
        }
        misses.increment();
        T value = loader.get();
        try {
            if (value != null) redis.opsForValue().set(full, mapper.writeValueAsString(value), ttl);
        } catch (RuntimeException e) {
            warn(e);
        }
        return value;
    }

    /** 새 calc_run 이 끝났을 때 호출 — 이전 세대 키는 TTL 로 사라집니다. */
    public void invalidateAll() {
        try {
            redis.opsForValue().increment(GEN_KEY);
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    private void warn(RuntimeException e) {
        long now = System.currentTimeMillis();
        if (now - lastWarn > 60_000) {
            lastWarn = now;
            log.warn("Redis 캐시를 쓸 수 없어 캐시 없이 조회합니다: {}", e.getMessage());
        }
    }
}
