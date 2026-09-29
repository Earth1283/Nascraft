package me.bounser.nascraft.web;

import java.util.concurrent.ConcurrentHashMap;

/** Token bucket per key (client IP). Refills continuously to {@code perMinute} tokens. */
public final class RateLimiter {

    private static final class Bucket {
        double tokens;
        long last;
        Bucket(double tokens, long last) { this.tokens = tokens; this.last = last; }
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final double capacity;
    private final double perMilli;

    public RateLimiter(int perMinute) {
        this.capacity = perMinute;
        this.perMilli = perMinute / 60_000.0;
    }

    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (b) {
            b.tokens = Math.min(capacity, b.tokens + (now - b.last) * perMilli);
            b.last = now;
            if (b.tokens < 1) return false;
            b.tokens -= 1;
            return true;
        }
    }

    /** Drops buckets that have refilled completely (idle clients). */
    public void sweep() {
        long now = System.currentTimeMillis();
        buckets.entrySet().removeIf(e -> {
            Bucket b = e.getValue();
            synchronized (b) { return b.tokens + (now - b.last) * perMilli >= capacity; }
        });
    }
}
