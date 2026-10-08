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

    /**
     * 비밀번호를 확인하기 전에 시도를 먼저 셈 — 잠금 확인과 실패 기록 사이가 비어 있으면 동시 요청이 모두 확인을 통과해
     * 상한을 넘겨 맞춰 볼 수 있었음(16개 동시 요청이 모두 비밀번호 확인까지 감, VERIFICATION 107). 잠겼으면 남은 초
     */
    public OptionalLong attempt(String username, String ip) {
        String user = userKey(username, ip), all = ipKey(ip);
        long u = limiter.hit(user, lock), a = limiter.hit(all, lock);
        if (u > maxFailures) return OptionalLong.of(limiter.ttlSeconds(user));
        if (a > maxFailures * 4L) return OptionalLong.of(limiter.ttlSeconds(all));
        return OptionalLong.empty();
    }

    /** 성공 — (아이디, IP) 실패를 지우고 IP 전체 카운터에서 이번 시도를 뺌 (IP 카운터는 실패만 셈) */
    public void success(String username, String ip) {
        limiter.reset(userKey(username, ip));
        limiter.release(ipKey(ip));
    }

    private static String userKey(String username, String ip) {
        return "br:login:fail:" + ip + ":" + username.toLowerCase(Locale.ROOT);
    }

    private static String ipKey(String ip) { return "br:login:ip:" + ip; }
}
