package com.larv.ide.performance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class MemoryAwareCacheTest {

    @Test
    public void evictsLeastRecentlyUsedWhenCountExceeded() {
        MemoryAwareCache<String, String> cache =
            new MemoryAwareCache<>(2, 1000, value -> value.length());
        cache.put("a", "aa");
        cache.put("b", "bb");
        assertEquals("aa", cache.get("a"));
        cache.put("c", "cc");
        // "b" was least recently used -> evicted.
        assertNull(cache.get("b"));
        assertEquals("aa", cache.get("a"));
        assertEquals("cc", cache.get("c"));
        assertEquals(2, cache.size());
    }

    @Test
    public void evictsWhenWeightExceededAndTracksWeight() {
        MemoryAwareCache<String, String> cache =
            new MemoryAwareCache<>(10, 9, value -> value.length());
        cache.put("a", "12345");
        cache.put("b", "12345");
        assertEquals(5L, cache.weight());
        assertNull(cache.get("a"));
        assertEquals("12345", cache.get("b"));
    }

    @Test
    public void rejectsValueLargerThanBudget() {
        MemoryAwareCache<String, String> cache =
            new MemoryAwareCache<>(10, 4, value -> value.length());
        cache.put("huge", "12345");
        assertNull(cache.get("huge"));
        assertEquals(0, cache.size());
    }

    @Test
    public void replacingValueUpdatesWeight() {
        MemoryAwareCache<String, String> cache =
            new MemoryAwareCache<>(10, 100, value -> value.length());
        cache.put("a", "12345");
        cache.put("a", "1");
        assertEquals(1L, cache.weight());
        assertEquals(1, cache.size());
        assertEquals("1", cache.get("a"));
    }

    @Test
    public void clearResetsWeightAndEntries() {
        MemoryAwareCache<String, String> cache =
            new MemoryAwareCache<>(10, 100, value -> value.length());
        cache.put("a", "123");
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0L, cache.weight());
    }
}
