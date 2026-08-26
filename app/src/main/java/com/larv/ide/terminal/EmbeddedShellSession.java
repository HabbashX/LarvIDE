package com.larv.ide.terminal;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import jackpal.androidterm.emulatorview.TermSession;

public class EmbeddedShellSession {

    private static final String TAG = "EmbeddedShellSession";

    private final TermSession session;
    private Process process;

    public EmbeddedShellSession(File workingDirectory) {
        session = new TermSession(false);
        try {
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh");
            builder.directory(workingDirectory != null ? workingDirectory
                : new File("/"));
            builder.redirectErrorStream(true);
            java.util.Map<String, String> env = builder.environment();
            env.put("TERM", "xterm-256color");
            env.put("HOME", workingDirectory != null
                ? workingDirectory.getAbsolutePath() : "/");
            env.put("PATH", "/system/bin:/system/xbin");
            process = builder.start();

            session.setTermIn(new MergedInput(process.getInputStream()));
            session.setTermOut(new ProcessStdout());
        } catch (IOException e) {
            Log.e(TAG, "Failed to start embedded shell", e);
            session.setTermIn(new java.io.ByteArrayInputStream(
                ("shell failed: " + e.getMessage() + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    public TermSession getSession() {
        return session;
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public void destroy() {
        try {
            if (session.isRunning()) {
                session.finish();
            }
        } catch (Exception ignored) {
        }
        if (process != null) {
            process.destroy();
        }
    }

    private static class MergedInput extends InputStream {
        private final InputStream source;
        MergedInput(InputStream source) { this.source = source; }
        @Override public int read() throws IOException { return source.read(); }
        @Override public int read(byte[] b) throws IOException { return source.read(b); }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            return source.read(b, off, len);
        }
        @Override public int available() throws IOException { return source.available(); }
    }

    private class ProcessStdout extends OutputStream {
        private final OutputStream target;
        ProcessStdout() throws IOException { this.target = process.getOutputStream(); }
        @Override public void write(int b) throws IOException { target.write(b); }
        @Override public void write(byte[] b) throws IOException { target.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            target.write(b, off, len);
        }
        @Override public void flush() throws IOException { target.flush(); }
    }
}
