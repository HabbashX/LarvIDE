package com.larv.ide.infrastructure.performance;

/**
 * Backward-compatible facade. Canonical implementation lives in {@link com.larv.ide.infrastructure.io.BufferPool}.
 */
public final class BufferPool {

    private BufferPool() {
    }

    public static byte[] acquire(int minSize) {
        return com.larv.ide.infrastructure.io.BufferPool.acquire(minSize);
    }

    public static void release(byte[] buf) {
        com.larv.ide.infrastructure.io.BufferPool.release(buf);
    }

    public static void clear() {
        com.larv.ide.infrastructure.io.BufferPool.clear();
    }
}
