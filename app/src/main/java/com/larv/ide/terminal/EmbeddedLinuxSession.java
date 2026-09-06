package com.larv.ide.terminal;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import jackpal.androidterm.emulatorview.TermSession;

/**
 * Terminal session preferring the embedded Linux bash (no external app).
 * Falls back to /system/bin/sh with a notice when the prefix isn't installed yet.
 */
public class EmbeddedLinuxSession {

    private static final String TAG = "EmbeddedLinuxSession";

    private final TermSession session;
    private Process process;
    private final boolean embedded;

    public EmbeddedLinuxSession(Context context, File workingDirectory) {
        Context app = context.getApplicationContext();
        TermSession s;
        boolean emb = false;
        Process proc = null;
        try {
            if (com.larv.ide.run.backend.embedded.EmbeddedRuntime.isEmbeddedReady(app)) {
                com.larv.ide.run.backend.embedded.EmbeddedLinuxBackend backend =
                    new com.larv.ide.run.backend.embedded.EmbeddedLinuxBackend(app);
                proc = backend.openShell(workingDirectory != null
                    ? workingDirectory.getAbsolutePath() : null);
                emb = true;
            } else {
                throw new IllegalStateException("prefix-missing");
            }
        } catch (Exception e) {
            Log.i(TAG, "Embedded bash unavailable, toybox fallback: " + e.getMessage());
            try {
                ProcessBuilder builder = new ProcessBuilder("/system/bin/sh");
                builder.directory(workingDirectory != null ? workingDirectory : new File("/"));
                builder.redirectErrorStream(true);
                java.util.Map<String, String> env = builder.environment();
                env.put("TERM", "xterm-256color");
                proc = builder.start();
            } catch (IOException io) {
                Log.e(TAG, "Fallback shell failed", io);
                s = new TermSession(false);
                s.setTermIn(new java.io.ByteArrayInputStream(
                    ("Shell unavailable. Download the Linux runtime from Languages & Runtimes.\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                session = s;
                embedded = false;
                return;
            }
        }
        process = proc;
        emb = emb && process != null;
        embedded = emb;
        s = new TermSession(false);
        final Process p = process;
        s.setTermIn(new InputStream() {
            private final InputStream src = p.getInputStream();
            @Override public int read() throws IOException { return src.read(); }
            @Override public int read(byte[] b) throws IOException { return src.read(b); }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                return src.read(b, off, len);
            }
            @Override public int available() throws IOException { return src.available(); }
        });
        final OutputStream target;
        OutputStream t;
        try {
            t = p.getOutputStream();
        } catch (Exception e) {
            t = null;
        }
        target = t;
        s.setTermOut(new OutputStream() {
            @Override public void write(int b) throws IOException {
                if (target != null) target.write(b);
            }
            @Override public void write(byte[] b) throws IOException {
                if (target != null) target.write(b);
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                if (target != null) target.write(b, off, len);
            }
            @Override public void flush() throws IOException {
                if (target != null) target.flush();
            }
        });
        session = s;
        if (!embedded) {
            // Greet toybox users with direction.
            try {
                target.write(("\n# Toybox shell — download the Linux runtime for clang/javac/python.\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                target.flush();
            } catch (Exception ignored) {
            }
        }
    }

    public TermSession getSession() {
        return session;
    }

    public boolean isEmbedded() {
        return embedded;
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public void destroy() {
        try {
            if (session.isRunning()) session.finish();
        } catch (Exception ignored) {
        }
        if (process != null) process.destroy();
    }
}
