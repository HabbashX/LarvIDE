package com.larv.ide.project;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded cache of content hashes used to avoid re-parsing text that has not
 * changed. Hashing is O(n) but far cheaper than running the symbol regexes and
 * allocating symbol lists for every completion request / tab switch.
 */
public final class ProjectIndexCache {

    private static final int DEFAULT_MAX_ENTRIES = 512;

    private final int maxEntries;
    private final Map<String, Integer> hashes;

    public ProjectIndexCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public ProjectIndexCache(int maxEntries) {
        this.maxEntries = Math.max(16, maxEntries);
        this.hashes = new LinkedHashMap<String, Integer>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                return size() > ProjectIndexCache.this.maxEntries;
            }
        };
    }

    public synchronized boolean isFresh(String path, String content) {
        if (path == null || content == null) return false;
        Integer known = hashes.get(path);
        return known != null && known == content.hashCode();
    }

    public synchronized void put(String path, String content) {
        if (path == null || content == null) return;
        hashes.put(path, content.hashCode());
    }

    public synchronized void remove(String path) {
        if (path != null) hashes.remove(path);
    }

    public synchronized void clear() {
        hashes.clear();
    }

    public synchronized int size() {
        return hashes.size();
    }
}
