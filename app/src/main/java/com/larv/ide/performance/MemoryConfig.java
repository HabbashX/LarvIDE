package com.larv.ide.performance;

import android.app.ActivityManager;
import android.content.Context;

/**
 * Central place for the IDE's memory-related limits. Values are derived from
 * the device's reported heap class instead of hard-coded constants, so a 3 GB
 * phone does not accept a cache sized for a flagship.
 */
public final class MemoryConfig {

    /** Never cache more than this many characters of file text. */
    private static final int MIN_TEXT_CACHE_CHARS = 256 * 1024;
    private static final int MAX_TEXT_CACHE_CHARS = 4 * 1024 * 1024;

    /** Completion results are small but numerous; keep a bounded number. */
    private static final int MIN_COMPLETION_ENTRIES = 32;
    private static final int MAX_COMPLETION_ENTRIES = 256;

    private final int textCacheChars;
    private final int completionEntries;
    private final int maxOpenDocuments;
    private final int ioBufferSize;

    private MemoryConfig(int textCacheChars, int completionEntries,
                         int maxOpenDocuments, int ioBufferSize) {
        this.textCacheChars = textCacheChars;
        this.completionEntries = completionEntries;
        this.maxOpenDocuments = maxOpenDocuments;
        this.ioBufferSize = ioBufferSize;
    }

    public static MemoryConfig defaults() {
        return new MemoryConfig(512 * 1024, 64, 12, PerformanceConfig.IO_BUFFER_BYTES);
    }

    public static MemoryConfig forContext(Context context) {
        int memoryClassMb = 64;
        if (context != null) {
            try {
                ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) memoryClassMb = am.getMemoryClass();
            } catch (RuntimeException ignored) {
                // Some OEM builds throw from getMemoryClass(); keep the safe default.
            }
        }
        int textChars = clamp(memoryClassMb * 1024 * 8,
            MIN_TEXT_CACHE_CHARS, MAX_TEXT_CACHE_CHARS);
        int completions = clamp(memoryClassMb / 2,
            MIN_COMPLETION_ENTRIES, MAX_COMPLETION_ENTRIES);
        // Bigger heaps can afford more open documents; small ones stay lean.
        int docs = clamp(memoryClassMb / 16 + 4, 4, 24);
        return new MemoryConfig(textChars, completions, docs,
            PerformanceConfig.IO_BUFFER_BYTES);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public int textCacheChars() {
        return textCacheChars;
    }

    public int completionEntries() {
        return completionEntries;
    }

    public int maxOpenDocuments() {
        return maxOpenDocuments;
    }

    public int ioBufferSize() {
        return ioBufferSize;
    }
}
