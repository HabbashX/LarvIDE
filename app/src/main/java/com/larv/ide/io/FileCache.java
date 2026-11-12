package com.larv.ide.io;

import com.larv.ide.performance.MemoryAwareCache;

/**
 * Bounded in-memory cache of file contents. Backed by {@link MemoryAwareCache},
 * so the cache is limited by both entry count and total characters instead of
 * an unbounded map.
 */
public final class FileCache {

    private final MemoryAwareCache<String, String> cache;

    public FileCache(int maxEntries) {
        this(maxEntries, 2L * 1024 * 1024);
    }

    public FileCache(int maxEntries, long maxChars) {
        this.cache = MemoryAwareCache.forText(maxEntries, maxChars);
    }

    public String get(String key) {
        return cache.get(key);
    }

    public void put(String key, String value) {
        cache.put(key, value);
    }

    public void remove(String key) {
        cache.remove(key);
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    public long weight() {
        return cache.weight();
    }
}
