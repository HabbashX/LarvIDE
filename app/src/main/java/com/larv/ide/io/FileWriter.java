package com.larv.ide.io;

import com.larv.ide.performance.PerformanceConfig;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Efficient file writer. Writes UTF-8 by default and supports append mode.
 * A buffered stream avoids one syscall per byte[] chunk while keeping the
 * pooled buffer size configurable.
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
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file, append);
             BufferedOutputStream out =
                 new BufferedOutputStream(fos, PerformanceConfig.IO_BUFFER_BYTES)) {
            out.write(data);
            out.flush();
            // Durability: the caller relies on this for atomic-save integrity.
            fos.getFD().sync();
        }
    }
}
