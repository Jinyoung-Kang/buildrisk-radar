package com.buildrisk.radar.common.cache;

import com.buildrisk.radar.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 무효화 직후 프로세스가 끝나도 지난 세대 키를 끝까지 지움 (VERIFICATION 223). batch-only CLI 는 Job 끝 무효화 직후 종료해,
 * 뒤에서 돌던 정리(cache-purge)가 Redis 연결이 닫히며 'Redis exception' 으로 끊기고 지난 세대가 TTL(10~30분) 동안 남았음.
 * 정리를 느리게 만들어(UNLINK 마다 40 ms) 기계 속도와 관계없이 '닫힐 때 아직 정리 중'을 만든다.
 */
class JsonCacheShutdownIT {
    private static final int KEYS = 3_000;            // UNLINK 500개씩 6번 × 40 ms ≈ 240 ms — 닫히는 데 걸리는 몇 ms 보다 훨씬 김

    /** 공유 컨텍스트(DB 0)의 br:gen · 세션과 섞이지 않게 DB 5 */
    private static ApplicationContextRunner redisOnly() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
                .withPropertyValues("spring.data.redis.url=" + IntegrationTest.redisUrl() + "/5");
    }

    private static ApplicationContextRunner withCache() {
        return redisOnly()
                .withBean("stringRedisTemplate", SlowUnlinkTemplate.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
                .withBean(JsonCache.class);
    }

    private static void seed(StringRedisTemplate redis) {
        redis.execute((RedisCallback<Void>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
        redis.opsForValue().set("br:gen", "7");
        Map<String, String> keys = new HashMap<>();
        for (int i = 0; i < KEYS; i++) keys.put("br:7:k" + i, "v");
        redis.opsForValue().multiSet(keys);
    }

    private static void assertPurged() {
        redisOnly().run(ctx -> {
            StringRedisTemplate r = ctx.getBean(StringRedisTemplate.class);
            assertThat(r.opsForValue().get("br:gen")).isEqualTo("8");            // 세대 INCR 은 원래도 됨
            assertThat(r.keys("br:7:*")).isEmpty();                            // 수정 전: 정리가 끊겨 수천 개 남음
        });
    }

    @Test
    void 무효화_직후_컨텍스트를_닫아도_지난_세대_키를_모두_지운다() {         // batch-only CLI: 요약 직후 SpringApplication.exit
        withCache().run(ctx -> {
            assertThat(ctx.getBean(LettuceConnectionFactory.class).getPhase()).isLessThan(JsonCache.PHASE);   // 연결보다 먼저 멈춤
            seed(ctx.getBean(StringRedisTemplate.class));
            ctx.getBean(JsonCache.class).invalidateAll();
        });
        assertPurged();
    }

    @Test
    void 종료_이벤트에서_무효화해도_연결이_닫히기_전에_정리를_끝낸다() {      // worker drain: ContextClosedEvent 안에서 Job 이 끝나며 무효화
        withCache().withBean(DrainLike.class).run(ctx -> seed(ctx.getBean(StringRedisTemplate.class)));
        assertPurged();
    }

    /** JobRequestWorker.onShutdown → drain → BatchLauncher.awaitFinished → invalidateAll 을 흉내 */
    static class DrainLike {
        private final JsonCache cache;

        DrainLike(JsonCache cache) { this.cache = cache; }

        @EventListener(ContextClosedEvent.class)
        void onClose() { cache.invalidateAll(); }
    }

    /** 정리를 느리게 — 운영에서는 키 공간 전체를 훑는 SCAN 이 같은 구실을 함 */
    static class SlowUnlinkTemplate extends StringRedisTemplate {
        SlowUnlinkTemplate(RedisConnectionFactory factory) { super(factory); }

        @Override
        public Long unlink(Collection<String> keys) {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.unlink(keys);
        }
    }
}
