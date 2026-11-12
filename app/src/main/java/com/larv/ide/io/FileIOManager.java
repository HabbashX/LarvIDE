package com.larv.ide.io;

import com.larv.ide.performance.PerformanceMonitor;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;

/**
 * Central entry point for file I/O. Combines reading, writing, caching and
 * debounced saving behind one facade, and reports timings to the performance
 * monitor so regressions are visible in debug logs.
 */
public final class FileIOManager {

    private final FileCache cache;
    private final SaveScheduler saveScheduler;
    private final PerformanceMonitor monitor;

    public FileIOManager(Executor executor, int cacheEntries, long debounceMs) {
        this(executor, new FileCache(cacheEntries), debounceMs, new PerformanceMonitor());
    }

    public FileIOManager(Executor executor, FileCache cache, long debounceMs,
                         PerformanceMonitor monitor) {
        this.cache = cache != null ? cache : new FileCache(32);
        this.saveScheduler = new SaveScheduler(executor, debounceMs);
        this.monitor = monitor != null ? monitor : new PerformanceMonitor();
    }

    public String read(File file) throws IOException {
        String key = pathKey(file);
        String cached = cache.get(key);
        if (cached != null) {
            monitor.count("file.read.cacheHit");
            return cached;
        }
        long start = PerformanceMonitor.now();
        String content = FileReader.read(file);
        monitor.record("file.read", PerformanceMonitor.now() - start);
        cache.put(key, content);
        return content;
    }

    public void write(File file, String content) throws IOException {
        long start = PerformanceMonitor.now();
        AtomicFileWriter.write(file, content);
        monitor.record("file.write", PerformanceMonitor.now() - start);
        if (file != null) cache.put(pathKey(file), content);
    }

    public void scheduleSave(String key, Runnable saveTask) {
        saveScheduler.schedule(key, saveTask);
    }

    public void cancelSave(String key) {
        saveScheduler.cancel(key);
    }

    public void invalidate(String path) {
        if (path != null) cache.remove(path);
    }

    public void clearCache() {
        cache.clear();
    }

    public com.larv.ide.io.FileCache cache() {
        return cache;
    }

    public PerformanceMonitor monitor() {
        return monitor;
    }

    public String performanceSnapshot() {
        return monitor.snapshot();
    }

    private static String pathKey(File file) {
        return file == null ? "" : file.getAbsolutePath();
    }
}
