package com.larv.ide.io;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Abstraction over a readable/writable document source. Covers both app-private
 * files and Storage Access Framework content URIs.
 */
public interface DocumentProvider {

    String read(Uri uri) throws IOException;

    void write(Uri uri, String content) throws IOException;

    boolean exists(Uri uri);

    final class Files implements DocumentProvider {
        private final Context context;

        public Files(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public String read(Uri uri) throws IOException {
            if (uri == null) throw new IOException("null uri");
            if ("file".equals(uri.getScheme()) || uri.getScheme() == null) {
                return FileReader.read(new File(uri.getPath()));
            }
            // content URI: stream through ContentResolver
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("cannot open " + uri);
                byte[] buf = BufferPool.acquire(
                    com.larv.ide.performance.PerformanceConfig.IO_BUFFER_BYTES);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return FileReader.decode(out.toByteArray());
            }
        }

        @Override
        public void write(Uri uri, String content) throws IOException {
            if (uri == null) throw new IOException("null uri");
            if ("file".equals(uri.getScheme()) || uri.getScheme() == null) {
                AtomicFileWriter.write(new File(uri.getPath()), content);
                return;
            }
            try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("cannot open " + uri);
                out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            }
        }

        @Override
        public boolean exists(Uri uri) {
            if (uri == null) return false;
            if ("file".equals(uri.getScheme()) || uri.getScheme() == null) {
                return new File(uri.getPath()).exists();
            }
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                return in != null;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
