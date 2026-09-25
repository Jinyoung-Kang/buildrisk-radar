package com.buildrisk.radar.common.security;

import com.buildrisk.radar.common.AppProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * 로그인 무차별 대입 방지: (아이디, IP) 조합 연속 실패 N회 → M분 잠금, IP 전체 실패 4N회 → 잠금(아이디 바꿔 가며 시도 방지).
 * 성공하면 (아이디, IP) 카운터를 지웁니다. 상태는 Redis — API 인스턴스가 여러 개여도 공유.
 */
@Component
public class LoginThrottle {
    private final RateLimiter limiter;
    private final int maxFailures;
    private final Duration lock;

    public LoginThrottle(RateLimiter limiter, AppProperties props) {
        this.limiter = limiter;
        this.maxFailures = Math.max(1, props.security().loginMaxFailures());
        this.lock = Duration.ofMinutes(Math.max(1, props.security().loginLockMinutes()));
    }

    /** 잠겨 있으면 남은 초 */
    public OptionalLong lockedFor(String username, String ip) {
        String user = userKey(username, ip);
        if (limiter.count(user) >= maxFailures) return OptionalLong.of(limiter.ttlSeconds(user));
        String all = ipKey(ip);
        if (limiter.count(all) >= maxFailures * 4L) return OptionalLong.of(limiter.ttlSeconds(all));
        return OptionalLong.empty();
    }

    public void failure(String username, String ip) {
        limiter.hit(userKey(username, ip), lock);
        limiter.hit(ipKey(ip), lock);
    }

    public void success(String username, String ip) { limiter.reset(userKey(username, ip)); }

    private static String userKey(String username, String ip) {
        return "br:login:fail:" + ip + ":" + username.toLowerCase(Locale.ROOT);
    }

    private static String ipKey(String ip) { return "br:login:ip:" + ip; }
}
