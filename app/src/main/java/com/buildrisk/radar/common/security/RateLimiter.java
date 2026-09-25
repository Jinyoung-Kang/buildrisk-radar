package com.buildrisk.radar.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 고정 창(fixed window) 카운터 — 여러 API 인스턴스가 같은 한도를 공유합니다.
 * Redis 가 없으면 제한하지 않고 통과(fail-open)해 조회 서비스가 멈추지 않게 합니다.
 */
@Component
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    /** 창 안의 현재 횟수(증가 후). Redis 오류면 -1 */
    public long hit(String key, Duration window) {
        try {
            Long n = redis.opsForValue().increment(key);
            if (n != null && n == 1L) redis.expire(key, window);
            return n == null ? -1 : n;
        } catch (RuntimeException e) {
            log.debug("레이트리밋 Redis 오류 — 통과: {}", e.getMessage());
            return -1;
        }
    }

    public long count(String key) {
        try {
            String v = redis.opsForValue().get(key);
            return v == null ? 0 : Long.parseLong(v);
        } catch (RuntimeException e) {
            return 0;
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
