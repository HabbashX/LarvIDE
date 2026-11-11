package com.larv.ide.io;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Debounced, coalesced save scheduler. Multiple schedule() calls for the same
 * key within the debounce window collapse into a single execution, and a stale
 * token never overwrites a newer save.
 */
public final class SaveScheduler {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor executor;
    private final long debounceMs;
    private final ConcurrentHashMap<String, Integer> tokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Runnable> pending = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    public SaveScheduler(Executor executor, long debounceMs) {
        this.executor = executor;
        this.debounceMs = debounceMs;
    }

    public void schedule(String key, Runnable task) {
        if (key == null || task == null) return;
        final int token = counter.incrementAndGet();
        tokens.put(key, token);
        Runnable prev = pending.remove(key);
        if (prev != null) handler.removeCallbacks(prev);
        final Runnable[] holder = new Runnable[1];
        holder[0] = () -> {
            pending.remove(key, holder[0]);
            Integer current = tokens.get(key);
            if (current == null || current != token) return;
            executor.execute(() -> {
                if (tokens.get(key) != token) return;
                try {
                    task.run();
                } finally {
                    tokens.remove(key, token);
                }
            });
        };
        pending.put(key, holder[0]);
        handler.postDelayed(holder[0], debounceMs);
    }

    public void cancel(String key) {
        if (key == null) return;
        tokens.remove(key);
        Runnable r = pending.remove(key);
        if (r != null) handler.removeCallbacks(r);
    }

    public void cancelAll() {
        tokens.clear();
        for (Runnable r : pending.values()) handler.removeCallbacks(r);
        pending.clear();
    }
}
