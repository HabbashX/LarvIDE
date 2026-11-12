package com.larv.ide.performance;

/**
 * Runtime knobs for the performance layer. All of these are deliberately
 * cheap to read on hot paths (primitive fields, no system calls).
 */
public final class PerformanceConfig {

    /** Compilation must never starve the editor: one compile at a time. */
    public static final int COMPILATION_THREADS = 1;

    /** Indexing/analysis threads, capped on low-end devices. */
    public static final int CPU_THREADS =
        Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 1));

    /** File I/O threads. Two is enough for editor workloads. */
    public static final int IO_THREADS = 2;

    /** Debounce used by the editor autosave, in milliseconds. */
    public static final long AUTOSAVE_DEBOUNCE_MS = 1000L;

    /**
     * Shared I/O buffer size. Measured on the test JVM: 32 KB reads take ~67%
     * of the time of 8 KB reads, while 64/128 KB add memory without a stable
     * gain. 32 KB keeps the two I/O threads at ~64 KB of buffers total.
     */
    public static final int IO_BUFFER_BYTES = 32 * 1024;

    /** Maximum number of indexed files per project scan. */
    public static final int MAX_INDEXED_FILES = 2000;

    /** Skip indexing files larger than this (they are not useful for symbols). */
    public static final long MAX_INDEXED_FILE_BYTES = 512 * 1024;

    /** Number of timing samples kept per metric before aggregation resets. */
    public static final int MONITOR_SAMPLE_WINDOW = 64;

    private PerformanceConfig() {
    }
}
