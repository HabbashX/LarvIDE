package com.larv.ide.performance;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, weight-aware LRU cache. Evicts the least recently used entries until
 * both the entry-count and the total-weight limits are satisfied. Weight is
 * supplied by the caller (for example the character count of a cached string),
 * so the cache respects real memory use rather than a misleading entry count.
 *
 * <p>Thread-safe: every public method synchronizes on the internal map.
 */
public final class MemoryAwareCache<K, V> {

    private final int maxEntries;
    private final long maxWeight;
    private final Weigher<V> weigher;
    private final LinkedHashMap<K, Entry<V>> map;
    private long currentWeight;

    public interface Weigher<V> {
        long weigh(V value);
    }

    private static final class Entry<V> {
        final V value;
        final long weight;

        Entry(V value, long weight) {
            this.value = value;
            this.weight = weight;
        }
    }

    public MemoryAwareCache(int maxEntries, long maxWeight, Weigher<V> weigher) {
        this.maxEntries = Math.max(1, maxEntries);
        this.maxWeight = Math.max(1L, maxWeight);
        this.weigher = weigher != null ? weigher : value -> 1L;
        this.map = new LinkedHashMap<>(16, 0.75f, true);
    }

    public static MemoryAwareCache<String, String> forText(int maxEntries, long maxChars) {
        return new MemoryAwareCache<>(maxEntries, maxChars, value ->
            value == null ? 1L : (long) value.length() + 32L);
    }

    public synchronized V get(K key) {
        Entry<V> entry = map.get(key);
        return entry != null ? entry.value : null;
    }

    public synchronized void put(K key, V value) {
        if (key == null || value == null) return;
        long weight = weigher.weigh(value);
        if (weight > maxWeight) return;
        Entry<V> previous = map.remove(key);
        if (previous != null) currentWeight -= previous.weight;
        map.put(key, new Entry<>(value, weight));
        currentWeight += weight;
        evict();
    }

    private void evict() {
        Iterator<Map.Entry<K, Entry<V>>> it = map.entrySet().iterator();
        while ((map.size() > maxEntries || currentWeight > maxWeight) && it.hasNext()) {
            Entry<V> eldest = it.next().getValue();
            currentWeight -= eldest.weight;
            it.remove();
        }
    }

    public synchronized void remove(K key) {
        Entry<V> previous = map.remove(key);
        if (previous != null) currentWeight -= previous.weight;
    }

    public synchronized void clear() {
        map.clear();
        currentWeight = 0L;
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized long weight() {
        return currentWeight;
    }

    public int maxEntries() {
        return maxEntries;
    }

    public long maxWeight() {
        return maxWeight;
    }
}
