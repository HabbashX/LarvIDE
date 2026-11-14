package com.larv.ide.run.backend.embedded;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
        // Check network connectivity before starting download
        ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm != null ? cm.getActiveNetworkInfo() : null;
        if (activeNetwork == null || !activeNetwork.isConnected()) {
            post(listener, () -> listener.onError("No internet connection. Please connect to WiFi and try again."));
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
                prefixDir.mkdirs();
                extract(cacheZip, prefixDir);
                processSymlinks(prefixDir);
                cacheZip.delete();
                makeExecutable(new File(prefixDir, "bin/bash"));

                // bash from Termux bootstrap depends on libandroid-support.so,
                // which is a separate package. Install it so bash can start.
                post(listener, () -> listener.onProgress("Installing libandroid-support…", 85));
                installLibAndroidSupport(prefixDir, listener);

                installing = false;
                EmbeddedRuntime.invalidateReadyCache();
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
        conn.setRequestProperty("User-Agent", "LarvIDE/1.0 (Android)");
        conn.setRequestProperty("Accept", "*/*");
        conn.connect();
        int code = conn.getResponseCode();
        if (code / 100 != 2) {
            throw new IllegalStateException("HTTP " + code + " when connecting to " + urlStr);
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
        conn.setRequestProperty("User-Agent", "LarvIDE/1.0 (Android)");
        conn.setRequestProperty("Accept", "*/*");
        conn.connect();
        int code = conn.getResponseCode();
        if (code / 100 != 2) {
            throw new IllegalStateException("HTTP " + code + " when connecting to " + urlStr);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
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
                    f.mkdirs();
                } else {
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
        String filesRoot = destDir.getParentFile().getAbsolutePath();
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
     * Download libandroid-support .deb from Termux repositories at install time,
     * extract libandroid-support.so, and place it in $PREFIX/lib/. Without this,
     * bash cannot start. No Termux app installation required — everything happens
     * inside LarvIDE.
     */
    private void installLibAndroidSupport(File prefixDir, Listener listener) throws Exception {
        post(listener, () -> listener.onProgress("Resolving package URL…", 86));

        // Step 1: Fetch Packages index to find the correct .deb filename
        String packagesText = downloadString(
            "https://packages.termux.org/apt/termux-main/dists/stable/main/binary-aarch64/Packages");

        String debFilename = null;
        for (String block : packagesText.split("\n\n")) {
            String pkgName = null;
            String filename = null;
            for (String line : block.split("\n")) {
                if (line.startsWith("Package: ")) {
                    pkgName = line.substring("Package: ".length()).trim();
                } else if (line.startsWith("Filename: ")) {
                    filename = line.substring("Filename: ".length()).trim();
                }
            }
            if ("libandroid-support".equals(pkgName) && filename != null) {
                debFilename = filename;
                break;
            }
        }

        if (debFilename == null) {
            throw new Exception("libandroid-support not found in Termux Packages index");
        }

        // Step 2: Download the .deb
        post(listener, () -> listener.onProgress("Downloading libandroid-support…", 88));
        // debFilename from Packages index is like "pool/main/liba/..." but full path is
        // "apt/termux-main/pool/main/liba/..."
        String debUrl = "https://packages.termux.org/apt/termux-main/" + debFilename.replaceFirst("^/+", "");
        byte[] debBytes = downloadBytes(debUrl, listener);

        // Step 3: Parse ar archive to find data.tar member
        if (debBytes.length < 8) throw new Exception("Deb too small");
        if (!new String(debBytes, 0, 8, StandardCharsets.US_ASCII).equals("!<arch>\n")) {
            throw new Exception("Not a valid ar archive (.deb)");
        }

        byte[] dataTar = null;
        int offset = 8;
        while (offset + 60 <= debBytes.length) {
            String name = new String(debBytes, offset, 16, StandardCharsets.US_ASCII).trim();
            String sizeStr = new String(debBytes, offset + 48, 12, StandardCharsets.US_ASCII).replaceAll("[^0-9]", "");
            long fileSize;
            try {
                fileSize = Long.parseLong(sizeStr);
            } catch (NumberFormatException e) {
                offset += 60;
                continue;
            }

            if (name.startsWith("data.tar")) {
                if (offset + 60 + fileSize > debBytes.length) {
                    throw new Exception("data.tar extends beyond deb");
                }
                dataTar = new byte[(int) fileSize];
                System.arraycopy(debBytes, offset + 60, dataTar, 0, (int) fileSize);
                break;
            }

            offset += 60 + (int) fileSize;
            if (fileSize % 2 != 0) offset += 1;
        }

        if (dataTar == null) {
            throw new Exception("data.tar not found in .deb");
        }

        // Step 4: Decompress (gzip or xz) to get raw tar
        byte[] tarData;
        String lowerName = new String(debBytes, offset, 16, StandardCharsets.US_ASCII).toLowerCase();
        try {
            if (lowerName.contains(".xz")) {
                org.tukaani.xz.XZInputStream xzIn =
                    new org.tukaani.xz.XZInputStream(new java.io.ByteArrayInputStream(dataTar));
                tarData = xzIn.readAllBytes();
                xzIn.close();
            } else {
                java.util.zip.GZIPInputStream gzIn =
                    new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(dataTar));
                tarData = gzIn.readAllBytes();
                gzIn.close();
            }
        } catch (java.util.zip.ZipException e) {
            tarData = dataTar; // not compressed
        }

        File libDir = new File(prefixDir, "lib");
        libDir.mkdirs();
        extractLibFromTar(tarData, libDir);

        File libSo = new File(libDir, "libandroid-support.so");
        if (!libSo.exists() || libSo.length() == 0) {
            throw new Exception("libandroid-support.so not found in package");
        }
        libSo.setExecutable(true, false);
    }

    /**
     * Parse a tar byte array and extract libandroid-support.so into destDir.
     */
    private static void extractLibFromTar(byte[] tarData, File destDir) throws Exception {
        int offset = 0;
        while (offset + 512 <= tarData.length) {
            byte[] header = new byte[512];
            System.arraycopy(tarData, offset, header, 0, 512);

            String name = new String(header, 0, 100, StandardCharsets.US_ASCII).trim();
            String sizeStr = new String(header, 124, 12, StandardCharsets.US_ASCII).replaceAll("[^0-7]", "");
            long fileSize;
            try {
                fileSize = Long.parseLong(sizeStr, 8);
            } catch (NumberFormatException e) {
                offset += 512;
                continue;
            }

            boolean isDir = header[156] == '5' || name.endsWith("/");

            String baseName = name.replaceAll(".*/", "");
            if ("libandroid-support.so".equals(baseName) && !isDir && fileSize > 0) {
                byte[] fileData = new byte[(int) fileSize];
                System.arraycopy(tarData, offset + 512, fileData, 0, (int) fileSize);
                File outFile = new File(destDir, baseName);
                try (java.io.OutputStream out = new java.io.FileOutputStream(outFile)) {
                    out.write(fileData);
                }
                outFile.setExecutable(true, false);
                return;
            }

            int paddedSize = (int) ((fileSize + 511) / 512 * 512);
            offset += 512 + paddedSize;
        }
    }

    /**
     * Download bytes into memory.
     */
    private static byte[] downloadBytes(String urlStr, Listener listener) throws Exception {
        HttpURLConnection conn =
            (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("User-Agent", "LarvIDE/1.0 (Android)");
        conn.setRequestProperty("Accept", "*/*");
        conn.connect();
        int code = conn.getResponseCode();
        if (code / 100 != 2) {
            throw new IllegalStateException("HTTP " + code + " when connecting to " + urlStr);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
        return baos.toByteArray();
    }

    /**
     * Parse ar archive, extract data.tar.gz member into a byte array.
     * ar format: "!<arch>\n" magic, then 60-byte headers followed by file data.
     */
    private static byte[] extractDataTarGzFromDeb(byte[] debBytes) throws Exception {
        // Verify magic
        if (debBytes.length < 8) throw new Exception("Deb too small");
        if (!new String(debBytes, 0, 8, StandardCharsets.US_ASCII).equals("!<arch>\n")) {
            throw new Exception("Not a valid ar archive (.deb)");
        }

        int offset = 8;
        while (offset + 60 <= debBytes.length) {
            // Parse header
            String name = new String(debBytes, offset, 16, StandardCharsets.US_ASCII).trim();
            String sizeStr = new String(debBytes, offset + 48, 12, StandardCharsets.US_ASCII).trim();
            long fileSize;
            try {
                fileSize = Long.parseLong(sizeStr);
            } catch (NumberFormatException e) {
                throw new Exception("Invalid ar header size: " + sizeStr);
            }

            if (name.startsWith("data.tar")) {
                if (offset + 60 + fileSize > debBytes.length) {
                    throw new Exception("data.tar.gz extends beyond deb");
                }
                byte[] dataTarGz = new byte[(int) fileSize];
                System.arraycopy(debBytes, offset + 60, dataTarGz, 0, (int) fileSize);
                return dataTarGz;
            }

            // Skip header + file data (padded to even boundary)
            offset += 60 + fileSize;
            if (fileSize % 2 != 0) offset += 1;
        }

        throw new Exception("data.tar.gz not found in .deb");
    }

    /**
     * Decompress gzip and extract libandroid-support.so from tar into destDir.
     */
    private static void extractLibAndroidSupportFromTar(byte[] dataTarGz, File destDir)
        throws Exception {
        try (GZIPInputStream gzIn = new GZIPInputStream(
                new BufferedInputStream(new ByteArrayInputStream(dataTarGz)))) {
            extractTarEntry(gzIn, "./usr/lib/aarch64-linux-gnu/libandroid-support.so", destDir);
            extractTarEntry(gzIn, "./lib/aarch64-linux-gnu/libandroid-support.so", destDir);
            extractTarEntry(gzIn, "usr/lib/aarch64-linux-gnu/libandroid-support.so", destDir);
            extractTarEntry(gzIn, "lib/aarch64-linux-gnu/libandroid-support.so", destDir);
        }
    }

    /**
     * Scan tar entries looking for a specific filename and extract it to destDir.
     * Tar format: 512-byte header, file data padded to 512-byte boundary.
     */
    private static void extractTarEntry(InputStream tarIn, String targetName, File destDir)
        throws Exception {
        byte[] header = new byte[512];
        while (true) {
            int hRead = readFully(tarIn, header);
            if (hRead < 512) break;

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

            if (name.equals(targetName) && !isDir) {
                // Extract file data
                byte[] fileData = new byte[(int) fileSize];
                int totalRead = 0;
                while (totalRead < fileData.length) {
                    int r = tarIn.read(fileData, totalRead, fileData.length - totalRead);
                    if (r < 0) break;
                    totalRead += r;
                }

                File outFile = new File(destDir, new File(name).getName());
                try (OutputStream out = new FileOutputStream(outFile)) {
                    out.write(fileData);
                }
                outFile.setExecutable(true, false);
                return;
            }

            long remaining = fileSize;
            byte[] skipBuf = new byte[8192];
            while (remaining > 0) {
                int r = tarIn.read(skipBuf, 0, (int) Math.min(skipBuf.length, remaining));
                if (r < 0) break;
                remaining -= r;
            }
            long pad = (512 - (fileSize % 512)) % 512;
            while (pad > 0) {
                int r = tarIn.read(skipBuf, 0, (int) Math.min(skipBuf.length, pad));
                if (r < 0) break;
                pad -= r;
            }
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

    private static void makeExecutable(File bash) {
        try {
            if (bash.exists()) {
                bash.setExecutable(true, false);
                File bin = bash.getParentFile();
                File[] files = bin != null ? bin.listFiles() : null;
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && !f.canExecute()) {
                            f.setExecutable(true, false);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
