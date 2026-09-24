package com.buildrisk.radar.adapters.common;

/** 호출 간 최소 간격 (예: DART 200ms). 여러 스레드가 같은 클라이언트를 써도 간격을 지킵니다. */
public final class Throttle {
    private final long minIntervalMs;
    private long nextAt;

    public Throttle(long minIntervalMs) { this.minIntervalMs = minIntervalMs; }

    public synchronized void acquire() {
        long now = System.currentTimeMillis();
        if (nextAt > now) HttpSupport.sleep(nextAt - now);
        nextAt = Math.max(now, nextAt) + minIntervalMs;
    }
}
