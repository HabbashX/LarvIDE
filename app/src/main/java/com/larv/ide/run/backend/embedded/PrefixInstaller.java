package com.larv.ide.run.backend.embedded;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
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

                // bash from Termux bootstrap depends on libandroid-support.so,
                // which is a separate package. Install it so bash can start.
                post(listener, () -> listener.onProgress("Installing libandroid-support…", 85));
                installLibAndroidSupport(prefixDir, listener);

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

    private static String downloadString(String urlStr) throws Exception {
        HttpURLConnection conn =
            (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);
        conn.connect();
        if (conn.getResponseCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + conn.getResponseCode());
        }
        try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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

    /**
     * Download libandroid-support .deb from Termux repositories and extract
     * libandroid-support.so into $PREFIX/lib/. Without this, bash cannot start.
     */
    private void installLibAndroidSupport(File prefixDir, Listener listener) throws Exception {
        // Step 1: Download Packages index to find libandroid-support .deb URL
        String packages = downloadString(
            "https://packages.termux.org/apt/termux-main/dists/stable/main/binary-aarch64/Packages");

        String debFilename = null;
        String[] blocks = packages.split("\n\n");
        for (String block : blocks) {
            boolean isLibAndroidSupport = false;
            for (String line : block.split("\n")) {
                if (line.startsWith("Package: libandroid-support")) {
                    isLibAndroidSupport = true;
                }
                if (isLibAndroidSupport && line.startsWith("Filename: ")) {
                    debFilename = line.substring("Filename: ".length()).trim();
                    break;
                }
            }
            if (debFilename != null) break;
        }

        if (debFilename == null) {
            throw new Exception("libandroid-support not found in Termux package index");
        }

        // Step 2: Download the .deb
        post(listener, () -> listener.onProgress("Downloading libandroid-support…", 88));
        File debFile = new File(appContext.getCacheDir(), "libandroid-support.deb");
        download("https://packages.termux.org" + debFilename, debFile, listener);

        // Step 3: Extract data.tar.gz from ar archive, then extract libandroid-support.so
        File libDir = new File(prefixDir, "lib");
        //noinspection ResultOfMethodCallIgnored
        libDir.mkdirs();

        try (InputStream debIn = new BufferedInputStream(new java.io.FileInputStream(debFile))) {
            extractDebDataTarGz(debIn, libDir, listener);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            debFile.delete();
        }

        File libSo = new File(libDir, "libandroid-support.so");
        if (!libSo.exists()) {
            throw new Exception("libandroid-support.so not found in package");
        }
        libSo.setExecutable(true, false);
    }

    /**
     * Parse a .deb ar archive, find data.tar.gz, decompress it, and extract
     * libandroid-support.so into destDir.
     */
    private static void extractDebDataTarGz(InputStream debIn, File destDir, Listener listener)
        throws Exception {
        // ar archive magic: "!<arch>\n"
        byte[] magic = new byte[8];
        int read = debIn.read(magic);
        if (read != 8 || !new String(magic, StandardCharsets.US_ASCII).equals("!<arch>\n")) {
            throw new Exception("Not a valid ar archive (.deb)");
        }

        byte[] dataTarGz = null;
        byte[] header = new byte[60];

        while (true) {
            int hRead = readFully(debIn, header);
            if (hRead < 60) break; // end of archive

            String name = new String(header, 0, 16, StandardCharsets.US_ASCII).trim();
            String sizeStr = new String(header, 48, 12, StandardCharsets.US_ASCII).trim();
            long fileSize;
            try {
                fileSize = Long.parseLong(sizeStr);
            } catch (NumberFormatException e) {
                throw new Exception("Invalid ar header size: " + sizeStr);
            }

            // Check magic bytes at offset 48-59: "\n"
            // Skip to file data
            byte[] fileData = new byte[(int) fileSize];
            int totalRead = 0;
            while (totalRead < fileData.length) {
                int r = debIn.read(fileData, totalRead, fileData.length - totalRead);
                if (r < 0) break;
                totalRead += r;
            }

            // Padding to even boundary
            if (fileSize % 2 != 0) {
                debIn.read(); // skip 1 padding byte
            }

            if (name.startsWith("data.tar")) {
                dataTarGz = fileData;
                break;
            }
        }

        if (dataTarGz == null) {
            throw new Exception("data.tar.gz not found in .deb");
        }

        // Decompress gzip and extract libandroid-support.so from tar
        try (GZIPInputStream gzIn = new GZIPInputStream(new BufferedInputStream(
            new java.io.ByteArrayInputStream(dataTarGz)))) {
            extractTarEntry(gzIn, "./usr/lib/aarch64-linux-gnu/libandroid-support.so", destDir);
            extractTarEntry(gzIn, "./lib/aarch64-linux-gnu/libandroid-support.so", destDir);
            extractTarEntry(gzIn, "usr/lib/aarch64-linux-gnu/libandroid-support.so", destDir);
            extractTarEntry(gzIn, "lib/aarch64-linux-gnu/libandroid-support.so", destDir);
        }
    }

    /**
     * Read exactly len bytes or until EOF.
     */
    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    /**
     * Scan tar entries looking for a specific filename and extract it to destDir.
     */
    private static void extractTarEntry(InputStream tarIn, String targetName, File destDir)
        throws Exception {
        byte[] header = new byte[512];
        while (true) {
            int hRead = readFully(tarIn, header);
            if (hRead < 512) break; // end of archive

            String name = new String(header, 0, 100, StandardCharsets.US_ASCII).trim();
            if (name.isEmpty()) continue;

            String sizeStr = new String(header, 124, 12, StandardCharsets.US_ASCII).trim();
            long fileSize;
            try {
                fileSize = Long.parseLong(sizeStr, 8);
            } catch (NumberFormatException e) {
                continue;
            }

            boolean isDir = (header[156] == '5') || name.endsWith("/");

            // Skip file data
            byte[] skipBuf = new byte[8192];
            long remaining = fileSize;
            while (remaining > 0) {
                int r = tarIn.read(skipBuf, 0, (int) Math.min(skipBuf.length, remaining));
                if (r < 0) break;
                remaining -= r;
            }
            // Pad to 512-byte boundary
            long pad = (512 - (fileSize % 512)) % 512;
            while (pad > 0) {
                int r = tarIn.read(skipBuf, 0, (int) Math.min(skipBuf.length, pad));
                if (r < 0) break;
                pad -= r;
            }

            if (!name.equals(targetName)) continue;

            if (isDir) continue;

            // Extract file
            byte[] fileData = new byte[(int) fileSize];
            int totalRead = 0;
            try (InputStream entryIn = tarIn) {
                while (totalRead < fileData.length) {
                    int r = entryIn.read(fileData, totalRead, fileData.length - totalRead);
                    if (r < 0) break;
                    totalRead += r;
                }
            }

            File outFile = new File(destDir, new File(name).getName());
            try (OutputStream out = new FileOutputStream(outFile)) {
                out.write(fileData);
            }
            outFile.setExecutable(true, false);
            return;
        }
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
