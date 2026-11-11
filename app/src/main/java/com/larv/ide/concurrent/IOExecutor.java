package com.larv.ide.concurrent;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded executor for blocking I/O. Never unbounded, never CallerRunsPolicy.
 */
public class IOExecutor extends ThreadPoolExecutor {
    public IOExecutor(int corePoolSize) {
        this(corePoolSize, Math.max(corePoolSize, 2));
    }

    public IOExecutor(int corePoolSize, int maximumPoolSize) {
        super(corePoolSize, maximumPoolSize, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            new ThreadPoolExecutor.AbortPolicy());
    }
}
