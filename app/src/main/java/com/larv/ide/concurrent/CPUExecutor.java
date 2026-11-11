package com.larv.ide.concurrent;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded executor for CPU-bound work (parsing, indexing, analysis).
 */
public class CPUExecutor extends ThreadPoolExecutor {
    public CPUExecutor() {
        this(Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    public CPUExecutor(int threads) {
        super(Math.max(1, threads), Math.max(1, threads), 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(128),
            new ThreadPoolExecutor.AbortPolicy());
    }
}
