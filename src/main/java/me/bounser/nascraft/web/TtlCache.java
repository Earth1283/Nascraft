package me.bounser.nascraft.web;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Time-based cache of prepared payloads. Concurrent requests for an expired
 * key wait for a single rebuild instead of all rebuilding at once.
 */
public final class TtlCache {

    private record Entry(Payload payload, long expires) {}

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final int maxEntries;

    public TtlCache(int maxEntries) { this.maxEntries = maxEntries; }

    public Payload get(String key, long ttlMillis, Supplier<Payload> builder) {
        long now = System.currentTimeMillis();
        Entry e = entries.get(key);
        if (e != null && e.expires > now) return e.payload;

        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            e = entries.get(key);
            now = System.currentTimeMillis();
            if (e != null && e.expires > now) return e.payload;
            Payload p = builder.get();
            if (entries.size() >= maxEntries) evictExpired(now);
            if (entries.size() < maxEntries) entries.put(key, new Entry(p, now + ttlMillis));
            return p;
        }
    }

    public void invalidate(String keyPrefix) {
        entries.keySet().removeIf(k -> k.startsWith(keyPrefix));
    }

    private void evictExpired(long now) {
        entries.entrySet().removeIf(en -> en.getValue().expires <= now);
        locks.keySet().retainAll(entries.keySet());
    }
}
