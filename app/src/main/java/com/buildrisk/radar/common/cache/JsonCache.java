package com.buildrisk.radar.common.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
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
        return get(key, ttl, mapper.getTypeFactory().constructType(type), loader, v -> true);
    }

    /** storeIf 가 거짓이면 결과를 돌려주되 저장하지 않음 — 빈 결과 · 없는 기간으로 키가 늘지 않게 (VERIFICATION 105) */
    public <T> T get(String key, Duration ttl, Class<T> type, Supplier<T> loader, Predicate<? super T> storeIf) {
        return get(key, ttl, mapper.getTypeFactory().constructType(type), loader, storeIf);
    }

    /** 제네릭 응답(Page&lt;CompanyRow&gt; 등)용 — Class 로 읽으면 항목이 Map 으로 풀려 숫자 표현이 달라질 수 있음 */
    public <T> T get(String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        return get(key, ttl, mapper.getTypeFactory().constructType(type), loader, v -> true);
    }

    public <T> T get(String key, Duration ttl, TypeReference<T> type, Supplier<T> loader, Predicate<? super T> storeIf) {
        return get(key, ttl, mapper.getTypeFactory().constructType(type), loader, storeIf);
    }

    private <T> T get(String key, Duration ttl, JavaType type, Supplier<T> loader, Predicate<? super T> storeIf) {
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
            if (value != null && storeIf.test(value)) redis.opsForValue().set(full, mapper.writeValueAsString(value), ttl);
        } catch (RuntimeException e) {
            warn(e);
        }
        return value;
    }

    /**
     * 기동할 때 세대를 올림 — 계산 방식이 바뀐 새 버전을 배포해도 옛 코드가 만든 결과가 TTL 동안 나가던 문제
     * (경보 성적표의 판정 기준을 고친 뒤 12개월 결과만 옛 값 — 실제 스택에서 확인). 인스턴스가 여럿이면 재시작마다 한 번씩 비워질 뿐
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() { invalidateAll(); }

    /** 새 calc_run 이 끝났을 때 호출 — 세대를 올리고 이전 세대 키는 뒤에서 지웁니다. */
    public void invalidateAll() {
        try {
            Long gen = redis.opsForValue().increment(GEN_KEY);
            if (gen != null && gen > 1) Thread.ofVirtual().name("cache-purge").start(() -> purge(gen - 1));
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    /**
     * 지난 세대의 키를 지움. TTL(10~30분)을 기다리면 무효화가 잦을 때 세대마다 키가 겹쳐 쌓여, 세션 · 로그인 잠금과 같은
     * Redis(128 MB, allkeys-lru)가 상한까지 찼음 — 정상 조합만으로도 세대당 약 31 MB (VERIFICATION 105).
     * 지우는 사이 이전 세대로 저장된 키가 조금 남을 수 있지만 TTL 로 사라집니다.
     */
    private void purge(long gen) {
        List<String> batch = new ArrayList<>();
        try (Cursor<String> keys = redis.scan(ScanOptions.scanOptions().match("br:" + gen + ":*").count(1000).build())) {
            while (keys.hasNext()) {
                batch.add(keys.next());
                if (batch.size() == 500) {
                    redis.unlink(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) redis.unlink(batch);
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
