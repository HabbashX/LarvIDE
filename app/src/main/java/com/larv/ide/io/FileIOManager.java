package com.larv.ide.io;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;

/**
 * Central entry point for file I/O. Combines reading, writing, caching and
 * debounced saving behind one facade.
 */
public final class FileIOManager {

    private final FileCache cache;
    private final SaveScheduler saveScheduler;

    public FileIOManager(Executor executor, int cacheEntries, long debounceMs) {
        this.cache = new FileCache(cacheEntries);
        this.saveScheduler = new SaveScheduler(executor, debounceMs);
    }

    public String read(File file) throws IOException {
        String cached = cache.get(pathKey(file));
        if (cached != null) return cached;
        String content = FileReader.read(file);
        cache.put(pathKey(file), content);
        return content;
    }

    public void write(File file, String content) throws IOException {
        AtomicFileWriter.write(file, content);
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

    public FileCache cache() {
        return cache;
    }

    private static String pathKey(File file) {
        return file == null ? "" : file.getAbsolutePath();
    }
}
