package com.larv.ide.io;

/**
 * Reusable thread-local byte buffers for I/O. Canonical location in the io package.
 * Delegates to performance.BufferPool for backward compatibility.
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

    public static void release(byte[] buf) {
        // Thread-local: nothing to do; buffer stays for reuse.
    }

    public static void clear() {
        BUFFERS.remove();
    }
}
