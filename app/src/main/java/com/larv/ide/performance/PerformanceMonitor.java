package com.larv.ide.performance;

import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight, always-on metrics for the IDE's own operations. Every metric is
 * two atomic counters (calls and total nanoseconds), so instrumentation costs
 * a couple of nanoseconds and allocates nothing.
 *
 * <p>Nothing is uploaded and no thread samples anything: the monitor only
 * records durations that callers report, and prints a summary on demand in
 * debug builds.
 */
public final class PerformanceMonitor {

    private static final String TAG = "LarvPerf";

    private static final class Metric {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong totalNanos = new AtomicLong();

        void record(long nanos) {
            calls.incrementAndGet();
            totalNanos.addAndGet(nanos);
        }
    }

    private final Map<String, Metric> metrics = new ConcurrentHashMap<>();

    private Metric metric(String name) {
        Metric m = metrics.get(name);
        if (m == null) {
            m = new Metric();
            Metric raced = metrics.putIfAbsent(name, m);
            if (raced != null) m = raced;
        }
        return m;
    }

    public void count(String name) {
        metric(name).calls.incrementAndGet();
    }

    public void record(String name, long nanos) {
        metric(name).record(Math.max(0L, nanos));
    }

    /** Wrap a blocking section: {@code long t = System.nanoTime(); ...; record(...)}. */
    public static long now() {
        return System.nanoTime();
    }

    public String snapshot() {
        StringBuilder sb = new StringBuilder(160);
        for (Map.Entry<String, Metric> e : metrics.entrySet()) {
            long calls = e.getValue().calls.get();
            long total = e.getValue().totalNanos.get();
            long avgMicros = calls == 0 ? 0 : (total / calls) / 1000L;
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append(": calls=").append(calls)
                .append(" avgUs=").append(avgMicros)
                .append(" totalMs=").append(total / 1_000_000L);
        }
        return sb.toString();
    }

    /** Debug-only: emits one log line per metric, never on the UI thread path. */
    public void logSnapshot() {
        if (!com.larv.ide.BuildConfig.DEBUG) return;
        String snapshot = snapshot();
        if (!snapshot.isEmpty()) {
            Log.d(TAG, snapshot);
        }
    }

    public void reset() {
        metrics.clear();
    }
}
