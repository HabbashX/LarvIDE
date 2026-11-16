package com.larv.ide.core.performance;

/**
 * Backward-compatible facade. Canonical implementation lives in {@link com.larv.ide.core.io.BufferPool}.
 */
public final class BufferPool {

    private BufferPool() {
    }

    public static byte[] acquire(int minSize) {
        return com.larv.ide.core.io.BufferPool.acquire(minSize);
    }

    public static void release(byte[] buf) {
        com.larv.ide.core.io.BufferPool.release(buf);
    }

    public static void clear() {
        com.larv.ide.core.io.BufferPool.clear();
    }
}
