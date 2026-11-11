package com.larv.ide.io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Efficient file writer. Writes UTF-8 by default and supports append mode.
 */
public final class FileWriter {

    private FileWriter() {
    }

    public static void write(File file, String content) throws IOException {
        write(file, content, false);
    }

    public static void write(File file, String content, boolean append) throws IOException {
        if (file == null) throw new IOException("null file");
        if (content == null) content = "";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream fos = new FileOutputStream(file, append)) {
            int off = 0;
            byte[] buf = BufferPool.acquire(8192);
            while (off < data.length) {
                int chunk = Math.min(buf.length, data.length - off);
                fos.write(data, off, chunk);
                off += chunk;
            }
            fos.flush();
            fos.getFD().sync();
        }
    }
}
