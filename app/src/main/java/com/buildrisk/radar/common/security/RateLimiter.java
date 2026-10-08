package com.buildrisk.radar.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis 고정 창(fixed window) 카운터 — 여러 API 인스턴스가 같은 한도를 공유합니다.
 * Redis 가 없으면 제한하지 않고 통과(fail-open)해 조회 서비스가 멈추지 않게 합니다.
 */
@Component
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    /**
     * 증가와 만료를 한 번에 — 따로 보내면 INCR 뒤 EXPIRE 만 실패했을 때 만료 없는 키가 남아 영구히 잠김(로그인 잠금).
     * 만료가 빠진 키를 만나도 다시 붙임 (VERIFICATION 107)
     */
    private static final RedisScript<Long> HIT = RedisScript.of("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 or redis.call('PTTL', KEYS[1]) < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return n""", Long.class);
    /** 0 보다 클 때만 줄임 — 만료된 키를 DECR 하면 만료 없는 -1 이 생김 */
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            local v = tonumber(redis.call('GET', KEYS[1]))
            if v and v > 0 then return redis.call('DECR', KEYS[1]) end
            return 0""", Long.class);

    /** 창 안의 현재 횟수(증가 후). Redis 오류면 -1 */
    public long hit(String key, Duration window) {
        try {
            Long n = redis.execute(HIT, List.of(key), String.valueOf(window.toMillis()));
            return n == null ? -1 : n;
        } catch (RuntimeException e) {
            log.debug("레이트리밋 Redis 오류 — 통과: {}", e.getMessage());
            return -1;
        }
    }

    /** 먼저 센 시도 하나를 되돌림 (로그인 성공) */
    public void release(String key) {
        try {
            redis.execute(RELEASE, List.of(key));
        } catch (RuntimeException e) {
            log.debug("레이트리밋 Redis 오류 — 무시: {}", e.getMessage());
        }
    }

    public long ttlSeconds(String key) {
        try {
            Long t = redis.getExpire(key);
            return t == null || t < 0 ? 60 : t;
        } catch (RuntimeException e) {
            return 60;
        }
    }

    public void reset(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException ignored) {
            // fail-open
        }
    }
}
