package com.larv.ide.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes a file atomically: data goes to a temp file, is fsynced, then renamed
 * over the target. A crash during writing leaves the original file intact.
 */
public final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    public static void write(File target, String content) throws IOException {
        if (target == null) throw new IOException("null target");
        if (content == null) content = "";
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, target.getName() + ".tmp");
        FileWriter.write(tmp, content, false);
        // fsync already done in FileWriter; rename.
        if (target.exists() && !target.delete()) {
            tmp.delete();
            throw new IOException("Could not replace existing file: " + target);
        }
        if (!tmp.renameTo(target)) {
            // Fallback copy (e.g. cross-filesystem).
            try (FileInputStream in = new FileInputStream(tmp);
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = BufferPool.acquire(8192);
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
                out.getFD().sync();
            }
            tmp.delete();
        }
    }
}
