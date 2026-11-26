package com.educore.ai;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded in-memory TTL cache with per-key expiration and safe key scoping.
 *
 * Redis-ready: keys are always namespaced by caller (e.g. "ai:" + userId + ":"
 * + hash) so a cached response can never leak across users. When a Redis
 * endpoint is configured later, this class is the single swap point.
 *
 * Graceful degradation: if the backing store ever fails, callers get a miss
 * and regenerate - the cache is an optimization, never a dependency.
 */
public final class TtlCache {

    private static final class Entry {
        final String value;
        final long expiresAt;
        Entry(String v, long ttlMs) {
            this.value = v;
            this.expiresAt = System.currentTimeMillis() + ttlMs;
        }
        boolean expired() { return System.currentTimeMillis() > expiresAt; }
    }

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final int maxSize;

    public TtlCache(long ttlMs, int maxSize) {
        this.ttlMs = ttlMs;
        this.maxSize = maxSize;
    }

    /** Returns cached value or null (expired entries are evicted lazily). */
    public String get(String key) {
        if (key == null) return null;
        Entry e = map.get(key);
        if (e == null) return null;
        if (e.expired()) {
            map.remove(key, e);
            return null;
        }
        return e.value;
    }

    public void put(String key, String value) {
        if (key == null || value == null) return;
        if (map.size() >= maxSize) evictExpired();
        if (map.size() >= maxSize) evictBatch(); // bounded fallback - never flush all
        map.put(key, new Entry(value, ttlMs));
    }

    /** Explicit invalidation (e.g. after data changes). */
    public void invalidate(String key) {
        if (key != null) map.remove(key);
    }

    public void invalidatePrefix(String prefix) {
        if (prefix == null) return;
        Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey().startsWith(prefix)) it.remove();
        }
    }

    public int size() {
        evictExpired();
        return map.size();
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> now > e.getValue().expiresAt);
    }

    /** Evicts the soonest-to-expire ~10% when still at capacity. */
    private void evictBatch() {
        int toRemove = Math.max(1, maxSize / 10);
        map.entrySet().stream()
                .sorted(java.util.Comparator.comparingLong(e -> e.getValue().expiresAt))
                .limit(toRemove)
                .forEach(e -> map.remove(e.getKey(), e.getValue()));
    }
}
