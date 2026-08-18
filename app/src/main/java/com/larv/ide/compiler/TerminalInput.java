package com.larv.ide.compiler;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class TerminalInput extends InputStream {
    private final BlockingQueue<Integer> queue = new LinkedBlockingQueue<>();
    private volatile boolean closed = false;

    public void writeLine(String line) {
        if (line == null || closed) return;
        for (int i = 0; i < line.length(); i++) {
            queue.offer((int) line.charAt(i));
        }
        queue.offer((int) '\n');
    }

    public void closeStream() {
        closed = true;
    }

    @Override
    public int read() throws IOException {
        while (!closed) {
            try {
                Integer b = queue.poll(200, TimeUnit.MILLISECONDS);
                if (b != null) return b;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return -1;
    }

    @Override
    public void close() throws IOException {
        closeStream();
    }
}