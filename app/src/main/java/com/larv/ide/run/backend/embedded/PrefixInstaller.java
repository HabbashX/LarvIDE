package com.larv.ide.run.backend.embedded;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads + extracts the Termux bootstrap (arm64) into private storage
 * on demand over WiFi. Keeps the APK small; true offline only after first install.
 *
 * <p>Bootstrap URL pattern (F-Droid / termux-app releases):
 * {@code https://github.com/termux/termux-app/releases/.../bootstrap-aarch64.zip}
 * Override via prefs key {@code embeddedBootstrapUrl} for testing/mirrors.
 */
public class PrefixInstaller {

    private static final String TAG = "PrefixInstaller";
    private static final String DEFAULT_BOOTSTRAP_URL =
        "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip";

    public interface Listener {
        void onProgress(String stage, int percent);
        void onComplete();
        void onError(String message);
    }

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean installing = false;

    public PrefixInstaller(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public PrefixStatus status() {
        if (installing) return PrefixStatus.INSTALLING;
        return EmbeddedRuntime.isEmbeddedReady(appContext)
            ? PrefixStatus.READY : PrefixStatus.NOT_INSTALLED;
    }

    public boolean isInstalling() {
        return installing;
    }

    public void installAsync(String overrideUrl, Listener listener) {
        if (installing) {
            post(listener, () -> listener.onError("Install already in progress"));
            return;
        }
        if (!EmbeddedRuntime.isArm64()) {
            post(listener, () -> listener.onError("Embedded Linux needs arm64 (this device: "
                + (android.os.Build.SUPPORTED_ABIS.length > 0
                    ? android.os.Build.SUPPORTED_ABIS[0] : "unknown") + ")"));
            return;
        }
        installing = true;
        executor.execute(() -> {
            try {
                String url = overrideUrl != null && !overrideUrl.isEmpty()
                    ? overrideUrl : DEFAULT_BOOTSTRAP_URL;
                post(listener, () -> listener.onProgress("Downloading bootstrap...", 5));
                File cacheZip = new File(appContext.getCacheDir(), "bootstrap-aarch64.zip");
                download(url, cacheZip, listener);
                post(listener, () -> listener.onProgress("Extracting…", 70));
                File prefixDir = new File(appContext.getFilesDir(), "usr");
                //noinspection ResultOfMethodCallIgnored
                prefixDir.mkdirs();
                extract(cacheZip, prefixDir);
                processSymlinks(prefixDir);
                //noinspection ResultOfMethodCallIgnored
                cacheZip.delete();
                makeExecutable(new File(prefixDir, "bin/bash"));
                installing = false;
                post(listener, () -> listener.onComplete());
            } catch (Exception e) {
                Log.e(TAG, "Prefix install failed", e);
                installing = false;
                post(listener, () -> listener.onError(e.getMessage()));
            }
        });
    }

    private static void post(Listener l, Runnable r) {
        if (l == null) return;
        new Handler(Looper.getMainLooper()).post(r);
    }

    private static void download(String urlStr, File out, Listener listener) throws Exception {
        HttpURLConnection conn =
            (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        conn.connect();
        if (conn.getResponseCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + conn.getResponseCode());
        }
        long total = conn.getContentLengthLong();
        long downloaded = 0;
        int lastPercent = 5;
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                downloaded += n;
                if (total > 0) {
                    int percent = 5 + (int) (downloaded * 65.0 / total);
                    if (percent > 70) percent = 70;
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        final int p = percent;
                        post(listener, () -> listener.onProgress("Downloading bootstrap...", p));
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void extract(File zip, File destDir) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new java.io.FileInputStream(zip)))) {
            ZipEntry entry;
            byte[] buf = new byte[64 * 1024];
            while ((entry = zis.getNextEntry()) != null) {
                File f = new File(destDir, entry.getName());
                if (!f.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                    throw new IllegalStateException("Zip slip: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.mkdirs();
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    f.getParentFile().mkdirs();
                    try (OutputStream o = new FileOutputStream(f)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) o.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void processSymlinks(File destDir) {
        File symlinks = new File(destDir, "SYMLINKS.txt");
        if (!symlinks.exists()) return;
        java.util.List<String> lines;
        try {
            lines = java.nio.file.Files.readAllLines(symlinks.toPath(),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            symlinks.delete();
            return;
        }
        String filesRoot = destDir.getParentFile().getAbsolutePath(); // .../files
        for (String raw : lines) {
            if (raw == null || raw.trim().isEmpty()) continue;
            String[] parts = raw.split("\u2190", 2);
            if (parts.length != 2) continue;
            String target = parts[0].trim();
            String link = parts[1].trim();
            if (target.isEmpty() || link.isEmpty()) continue;
            if (link.startsWith("./")) link = link.substring(2);
            File linkFile = new File(destDir, link);
            if (linkFile.exists()) continue;
            File parent = linkFile.getParentFile();
            if (parent != null) parent.mkdirs();
            String resolvedTarget = target;
            if (target.startsWith("/data/data/com.termux/files")) {
                resolvedTarget = filesRoot + target.substring("/data/data/com.termux/files".length());
            }
            java.nio.file.Path targetPath = java.nio.file.Paths.get(resolvedTarget);
            if (!targetPath.isAbsolute()) {
                targetPath = linkFile.getParentFile().toPath().resolve(resolvedTarget).normalize();
            }
            try {
                java.nio.file.Files.createSymbolicLink(linkFile.toPath(), targetPath);
            } catch (Exception ignored) {
            }
        }
        symlinks.delete();
    }
    private static void makeExecutable(File bash) {
        try {
            if (bash.exists()) {
                //noinspection ResultOfMethodCallIgnored
                bash.setExecutable(true, false);
                // Best-effort: mark common bins executable too.
                File bin = bash.getParentFile();
                File[] files = bin != null ? bin.listFiles() : null;
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && !f.canExecute()) {
                            //noinspection ResultOfMethodCallIgnored
                            f.setExecutable(true, false);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
