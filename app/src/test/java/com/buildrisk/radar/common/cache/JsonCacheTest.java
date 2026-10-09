package com.buildrisk.radar.common.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JsonCacheTest {
    @Test
    @SuppressWarnings("unchecked")
    void 무효화가_실패하면_Redis_가_돌아온_뒤_첫_조회에서_다시_올린다() {
        // Job 이 끝나는 순간 Redis 가 잠깐 끊기면 세대를 못 올린 채 넘어가, 목록 10분 · 성적표 30분 동안 옛 값이 나갔음 (VERIFICATION 138)
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("br:gen")).thenThrow(new RedisConnectionFailureException("끊김")).thenReturn(8L);
        when(ops.get(anyString())).thenReturn(null);
        JsonCache cache = new JsonCache(redis, JsonMapper.builder().build(), new SimpleMeterRegistry());

        cache.invalidateAll();                                                   // 실패
        assertThat(cache.get("k", Duration.ofMinutes(1), String.class, () -> "v")).isEqualTo("v");
        verify(ops, times(2)).increment("br:gen");                               // 다음 조회 전에 다시 올림
        cache.get("k", Duration.ofMinutes(1), String.class, () -> "v");
        verify(ops, times(2)).increment("br:gen");                               // 한 번만
        verify(ops, times(2)).set(eq("br:0:k"), any(), any(Duration.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 조회가_없는_프로세스에서도_실패한_무효화를_뒤에서_다시_올린다() {
        // Job 이 끝날 때의 무효화는 worker 에서 일어나는데, 다시 올리는 곳이 조회(api)뿐이라 worker 에서 실패하면 아무도
        // 다시 하지 않았음 — api 는 옛 세대 값을 TTL 동안 그대로 냄 (VERIFICATION 154)
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("br:gen")).thenThrow(new RedisConnectionFailureException("끊김"))
                .thenThrow(new RedisConnectionFailureException("아직 끊김")).thenReturn(8L);
        JsonCache cache = new JsonCache(redis, JsonMapper.builder().build(), new SimpleMeterRegistry(), Duration.ofMillis(50));

        cache.invalidateAll();                                                   // 실패 — 조회는 하지 않음
        verify(ops, timeout(3_000).times(3)).increment("br:gen");                // 뒤에서 다시 시도해 성공
        verify(ops, after(300).times(3)).increment("br:gen");                    // 성공한 뒤에는 멈춤
    }
}

