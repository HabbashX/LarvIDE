package com.larv.ide.performance;

/**
 * Backward-compatible facade. Canonical implementation lives in {@link com.larv.ide.io.BufferPool}.
 */
public final class BufferPool {

    private BufferPool() {
    }

    public static byte[] acquire(int minSize) {
        return com.larv.ide.io.BufferPool.acquire(minSize);
    }

    public static void release(byte[] buf) {
        com.larv.ide.io.BufferPool.release(buf);
    }

    public static void clear() {
        com.larv.ide.io.BufferPool.clear();
    }
}
