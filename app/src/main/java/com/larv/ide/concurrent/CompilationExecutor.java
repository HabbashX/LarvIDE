package com.larv.ide.concurrent;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Single-threaded executor for compilation, keeping it isolated from editor latency.
 */
public class CompilationExecutor extends ThreadPoolExecutor {
    public CompilationExecutor() {
        super(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(16),
            new ThreadPoolExecutor.AbortPolicy());
    }
}
