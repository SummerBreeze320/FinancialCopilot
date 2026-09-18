package com.financial.copilot.agent.core.infra.dag.runtime.checkpoint;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/** Shared lazy TTL and LRU policy for immutable process-local projections. */
public final class BoundedExpiringStore<K, V> {
    private record Entry<V>(V value, Instant expiresAt, long lastAccess) {}
    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private long sequence;

    public BoundedExpiringStore(Clock clock) { this.clock = clock; }

    // ponytail: serialize at most 1000 entries; use per-key locking only if contention warrants it.
    public synchronized V get(K key) {
        purge();
        Entry<V> entry = entries.get(key);
        if (entry == null) return null;
        entries.put(key, new Entry<>(entry.value(), entry.expiresAt(), ++sequence));
        return entry.value();
    }

    /** A null ttl preserves expiry; a null result deletes the key. */
    public synchronized void update(K key, Duration ttl, UnaryOperator<V> change) {
        purge();
        Entry<V> old = entries.get(key);
        V value = change.apply(old == null ? null : old.value());
        if (value == null) { entries.remove(key); return; }
        if (ttl == null && old == null) return;
        Instant expiresAt = ttl == null ? old.expiresAt() : clock.instant().plus(ttl);
        entries.put(key, new Entry<>(value, expiresAt, ++sequence));
        if (entries.size() > 1000) {
            K oldest = entries.entrySet().stream()
                .min(Comparator.comparingLong(e -> e.getValue().lastAccess())).orElseThrow().getKey();
            entries.remove(oldest);
        }
    }

    public synchronized void remove(K key) { purge(); entries.remove(key); }

    private void purge() {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
    }
}
