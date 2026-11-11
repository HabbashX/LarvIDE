package com.larv.ide.io;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded in-memory cache of file contents. LRU eviction by access order.
 * Stores at most {@code maxEntries} documents.
 */
public final class FileCache {

    private final int maxEntries;
    private final LinkedHashMap<String, String> cache;

    public FileCache(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        this.cache = new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > FileCache.this.maxEntries;
            }
        };
    }

    public synchronized String get(String key) {
        return cache.get(key);
    }

    public synchronized void put(String key, String value) {
        if (key == null || value == null) return;
        cache.put(key, value);
    }

    public synchronized void remove(String key) {
        cache.remove(key);
    }

    public synchronized void clear() {
        cache.clear();
    }

    public synchronized int size() {
        return cache.size();
    }
}
