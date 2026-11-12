package com.larv.ide.performance;

import android.content.ComponentCallbacks2;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

/**
 * Owns the process-wide, memory-sensitive caches and reacts to Android memory
 * pressure. Nothing here is a new source of truth: caches only hold derived
 * data (file text, completion lists), so clearing them is always safe.
 */
public final class CacheManager {

    private final MemoryConfig config;
    private final MemoryAwareCache<String, String> textCache;
    private final MemoryAwareCache<String, Object> computationCache;

    public CacheManager(MemoryConfig config) {
        this.config = config != null ? config : MemoryConfig.defaults();
        this.textCache = MemoryAwareCache.forText(
            64, this.config.textCacheChars());
        this.computationCache = new MemoryAwareCache<>(
            this.config.completionEntries(), this.config.completionEntries(),
            value -> 1L);
    }

    @NonNull
    @Contract("_ -> new")
    public static CacheManager create(android.content.Context context) {
        return new CacheManager(MemoryConfig.forContext(context));
    }

    public MemoryConfig config() { return config; }

    public MemoryAwareCache<String, String> text() { return textCache; }

    public MemoryAwareCache<String, Object> computations() { return computationCache; }

    /**
     * Trim caches according to the framework's memory-pressure level.
     * Returns true when editor-level resources should also be released.
     */
    public boolean onTrimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) { textCache.clear(); computationCache.clear();
            return true;
        }

        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) { textCache.clear(); return false; }

        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            computationCache.clear();
            return false;
        }
        return false;
    }

    public void clearAll() { textCache.clear(); computationCache.clear();}
}
