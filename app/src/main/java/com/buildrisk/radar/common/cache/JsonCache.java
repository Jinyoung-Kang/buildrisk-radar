package com.buildrisk.radar.common.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Redis 조회 캐시. 키 앞에 '세대(generation)' 번호를 붙여 계산 run 이 끝날 때 INCR 한 번으로
 * 전체를 무효화합니다 (calc_run 단위 무효화). Redis 가 없으면 캐시 없이 동작합니다.
 *
 * <p>SmartLifecycle phase {@value #PHASE}: 종료 때 Lettuce 연결 팩토리(phase 0)보다 먼저 멈추며 뒤에서 돌던 지난 세대 정리를 기다립니다.
 * Spring 은 ContextClosedEvent 리스너(worker drain)를 모두 마친 뒤 phase 가 큰 것부터 멈추므로, drain · 처리 중 요청이 시작한 정리까지
 * 기다린 다음 연결이 닫힙니다 — batch-only CLI 는 무효화 직후 종료해 정리가 매번 'Redis exception' 으로 끊겼음 (VERIFICATION 223)
 */
@Component
public class JsonCache implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(JsonCache.class);
    private static final String GEN_KEY = "br:gen";
    /** LettuceConnectionFactory 의 기본 phase(0)보다 크고, 웹 서버 정지(DEFAULT_PHASE − 2048)보다 작음 */
    static final int PHASE = 1;
    /** 종료 때 정리를 기다리는 상한 — SCAN 이 키 공간 전체를 훑으므로 대기는 지난 세대가 아니라 Redis 전체 키 수에 비례 */
    static final Duration PURGE_WAIT = Duration.ofSeconds(5);
    /** 돌고 있는 지난 세대 정리 — 스레드가 아니라 끝남 표시를 둠: 넣은 뒤 시작 전인 스레드를 join 하면 예외가 나 나머지를 기다리지 못함 */
    private final Set<CompletableFuture<Void>> purging = ConcurrentHashMap.newKeySet();
    private volatile boolean running;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Counter hits;
    private final Counter misses;
    private final Counter shared;
    /** 계산 중인 키 → 그 결과 (같은 키의 동시 계산을 하나로) */
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();
    private final Counter errors;
    private volatile long lastWarn;
    /** 세대를 올리지 못한 무효화 — Redis 가 돌아온 뒤 첫 조회 전에 다시 올림 */
    private volatile boolean invalidatePending;
    private final Duration retryDelay;
    private final AtomicBoolean retrying = new AtomicBoolean();

    /** 적중률은 Prometheus 의 buildrisk_cache_requests_total{result} 로 — 캐시가 실제로 DB 부하를 덜어 주는지 확인 (ADR-022) */
    @Autowired
    public JsonCache(StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry registry) {
        this(redis, mapper, registry, Duration.ofSeconds(1));
    }

    /** retryDelay: 실패한 무효화를 다시 시도하는 첫 간격(두 배씩 30초까지) — 테스트는 짧게 */
    JsonCache(StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry registry, Duration retryDelay) {
        this.redis = redis;
        this.mapper = mapper;
        this.hits = counter(registry, "hit");
        this.misses = counter(registry, "miss");
        this.shared = counter(registry, "shared");
        this.errors = counter(registry, "error");
        this.retryDelay = retryDelay;
    }

    private static Counter counter(MeterRegistry registry, String result) {
        return Counter.builder("buildrisk.cache.requests").tag("cache", "redis").tag("result", result)
                .description("조회 캐시 요청 (hit · miss · shared = 같은 키를 계산 중인 요청의 결과를 받음 · error = Redis 불가로 캐시 없이 조회)")
                .register(registry);
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
        if (invalidatePending) tryInvalidate();
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
        // 같은 키를 이미 계산 중이면 그 결과를 기다림 — 무효화 직후 무거운 조회(성적표 0.4~0.7초)를 동시 요청이 각자 다시 계산해
        // 연결 풀(AWS 4)을 나눠 먹었음: 동시 20요청 = 계산 20번 (VERIFICATION 194). 프로세스 안에서만(다른 인스턴스와는 나누지 않음)
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> running = inflight.putIfAbsent(full, mine);
        if (running != null) {
            shared.increment();
            return join(running);
        }
        misses.increment();
        try {
            T value = loader.get();
            try {
                if (value != null && storeIf.test(value)) redis.opsForValue().set(full, mapper.writeValueAsString(value), ttl);
            } catch (RuntimeException e) {
                warn(e);
            }
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(full, mine);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T join(CompletableFuture<Object> running) {
        try {
            return (T) running.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            if (e.getCause() instanceof Error err) throw err;
            throw e;
        }
    }

    /**
     * 기동할 때 세대를 올림 — 계산 방식이 바뀐 새 버전을 배포해도 옛 코드가 만든 결과가 TTL 동안 나가던 문제
     * (경보 성적표의 판정 기준을 고친 뒤 12개월 결과만 옛 값 — 실제 스택에서 확인). 인스턴스가 여럿이면 재시작마다 한 번씩 비워질 뿐
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() { invalidateAll(); }

    /** 새 calc_run 이 끝났을 때 호출 — 세대를 올리고 이전 세대 키는 뒤에서 지웁니다. */
    public void invalidateAll() {
        if (!tryInvalidate()) retryInBackground();
    }

    /**
     * 세대를 올림. Job 이 끝나는 순간 Redis 가 잠깐 끊기면 옛 값이 TTL 동안 나가므로 실패를 표시해 두고 다시 함 —
     * 조회(api)는 다음 조회 전에(138), 조회가 없는 worker 는 뒤에서(154)
     */
    private boolean tryInvalidate() {
        try {
            Long gen = redis.opsForValue().increment(GEN_KEY);
            invalidatePending = false;
            if (gen != null && gen > 1) purgeInBackground(gen - 1);
            return true;
        } catch (RuntimeException e) {
            invalidatePending = true;
            warn(e);
            return false;
        }
    }

    /** 지난 세대 정리는 뒤에서 — 응답 · Job 스레드를 막지 않음. 종료 때는 stop() 이 이 스레드들을 기다림 */
    private void purgeInBackground(long gen) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        purging.add(done);
        try {
            Thread.ofVirtual().name("cache-purge").start(() -> {
                try {
                    purge(gen);
                } finally {
                    purging.remove(done);
                    done.complete(null);
                }
            });
        } catch (RuntimeException | Error e) {
            purging.remove(done);
            throw e;
        }
    }

    @Override
    public void start() { running = true; }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public int getPhase() { return PHASE; }

    /**
     * 연결이 닫히기 전에 — 실패해 표시해 둔 무효화를 한 번 더 올리고(곧 끝나는 CLI 에서는 뒤에서 다시 하는 스레드가 JVM 과 함께 사라짐, 154),
     * 돌고 있는 정리를 {@link #PURGE_WAIT} 까지 기다림. 멈춘 뒤에도 정리는 계속 뒤에서 시작됨(stop → start 뒤에도 그대로)
     */
    @Override
    public void stop() {
        running = false;
        if (invalidatePending) tryInvalidate();
        try {
            CompletableFuture.allOf(List.copyOf(purging).toArray(new CompletableFuture<?>[0])).get(PURGE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("지난 세대 캐시 정리를 {}초 안에 끝내지 못함 — 남은 키는 TTL 로 사라집니다", PURGE_WAIT.toSeconds());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.warn("지난 세대 캐시 정리를 기다리지 못함: {}", e.getMessage());   // 정리는 실패해도 끝남 표시를 정상으로 남기므로 오지 않음
        }
    }

    /** 실패한 무효화를 뒤에서 다시 (간격 두 배씩 30초까지). 가장 긴 TTL(30분)이 지나면 옛 값도 사라지므로 그때 멈춤 */
    private void retryInBackground() {
        if (!retrying.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("cache-invalidate-retry").start(() -> {
            boolean gaveUp = false;
            try {
                long deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos();
                long delay = retryDelay.toMillis();
                while (invalidatePending && System.nanoTime() < deadline) {
                    Thread.sleep(delay);
                    if (invalidatePending) tryInvalidate();
                    delay = Math.min(delay * 2, 30_000);
                }
                gaveUp = invalidatePending;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                gaveUp = true;
            } finally {
                retrying.set(false);
            }
            if (!gaveUp && invalidatePending) retryInBackground();               // 성공한 직후 새로 실패했으면
        });
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
