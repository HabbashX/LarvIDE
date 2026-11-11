package com.larv.ide.performance;

/**
 * Reusable thread-local byte buffers for I/O. Avoids allocating a fresh buffer
 * on every small read/write. Buffers only grow; they are never shrunk, so a
 * thread that once read a large file keeps a large buffer — acceptable for the
 * handful of I/O threads, and far better than per-call allocation.
 */
public final class BufferPool {

    private static final int DEFAULT_SIZE = 8 * 1024;

    private static final ThreadLocal<byte[]> BUFFERS = new ThreadLocal<>();

    private BufferPool() {
    }

    public static byte[] acquire(int minSize) {
        byte[] buf = BUFFERS.get();
        if (buf == null || buf.length < minSize) {
            int size = Math.max(minSize, DEFAULT_SIZE);
            int cap = DEFAULT_SIZE;
            while (cap < size) cap <<= 1;
            buf = new byte[cap];
            BUFFERS.set(buf);
        }
        return buf;
    }

    public static void clear() {
        BUFFERS.remove();
    }
}
