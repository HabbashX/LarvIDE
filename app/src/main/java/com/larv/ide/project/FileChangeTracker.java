package com.larv.ide.project;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers the last-indexed (mtime, size) of project files so a project scan
 * can skip files that have not changed. Filesystem metadata is read once per
 * scan; unchanged files are never opened or parsed again.
 *
 * <p>Bounded so a large project cannot grow this structure without limit.
 */
public final class FileChangeTracker {

    private static final int DEFAULT_MAX_ENTRIES = 4000;

    private static final class Stamp {
        final long modified;
        final long size;

        Stamp(long modified, long size) {
            this.modified = modified;
            this.size = size;
        }
    }

    private final int maxEntries;
    private final Map<String, Stamp> stamps;

    public FileChangeTracker() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public FileChangeTracker(int maxEntries) {
        this.maxEntries = Math.max(16, maxEntries);
        this.stamps = new LinkedHashMap<String, Stamp>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Stamp> eldest) {
                return size() > FileChangeTracker.this.maxEntries;
            }
        };
    }

    public synchronized boolean hasChanged(File file) {
        if (file == null) return false;
        Stamp stamp = stamps.get(key(file));
        return stamp == null
            || stamp.modified != file.lastModified()
            || stamp.size != file.length();
    }

    public synchronized void markIndexed(File file) {
        if (file == null) return;
        stamps.put(key(file), new Stamp(file.lastModified(), file.length()));
    }

    public synchronized void remove(File file) {
        if (file != null) stamps.remove(key(file));
    }

    public synchronized void clear() {
        stamps.clear();
    }

    public synchronized int size() {
        return stamps.size();
    }

    private static String key(File file) {
        return file.getAbsolutePath();
    }
}
