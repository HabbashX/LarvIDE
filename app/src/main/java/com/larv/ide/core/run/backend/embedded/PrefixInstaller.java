package com.larv.ide.core.run.backend.embedded;

import android.annotation.SuppressLint;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads + extracts the Termux bootstrap (arm64) into private storage
 * on demand over WiFi. Keeps the APK small; true offline only after first install.
 *
 * <p>Besides the bootstrap this installs the shared libraries the bootstrap and
 * proot need but do not ship: {@code libandroid-support} (bash), {@code libtalloc}
 * and {@code libandroid-shmem} (proot). All three are pulled from the Termux
 * package index, so no Termux app installation is required.
 *
 * <p>Bootstrap URL pattern (F-Droid / termux-app releases):
 * {@code https://github.com/termux/termux-app/releases/.../bootstrap-aarch64.zip}
 * Override via prefs key {@code embeddedBootstrapUrl} for testing/mirrors.
 */
public class PrefixInstaller {

    private static final String TAG = "PrefixInstaller";
    private static final String DEFAULT_BOOTSTRAP_URL =
        "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip";
    private static final String REPO_BASE = "https://packages.termux.org/apt/termux-main/";
    private static final String PACKAGES_INDEX_URL =
        REPO_BASE + "dists/stable/main/binary-aarch64/Packages";

    /**
     * Package name -> file that must exist in {@code $PREFIX/lib}. The first
     * entry is bash's dependency; the other two are proot's DT_NEEDED entries.
     */
    private static final String[][] RUNTIME_LIBRARIES = {
        {"libandroid-support", "libandroid-support.so"},
        {"libtalloc", "libtalloc.so.2"},
        {"libandroid-shmem", "libandroid-shmem.so"},
    };

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

    /**
     * Full install: downloads the bootstrap when it is missing, then makes sure
     * the runtime libraries are present. Re-running it on an existing prefix only
     * repairs the missing pieces (a few dozen KB), so it is also the upgrade path
     * for installs created before proot's dependencies were packaged.
     */
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
                File prefixDir = new File(appContext.getFilesDir(), "usr");
                prefixDir.mkdirs();
                File bash = new File(prefixDir, "bin/bash");
                File symlinkManifest = new File(prefixDir, "SYMLINKS.txt");
                boolean bootstrapReady = bash.isFile() && bash.length() > 0;

                // Prefixes created before the symlink fix deleted SYMLINKS.txt
                // and wrote host-absolute links (/data/user/0/...) that proot
                // cannot resolve (e.g. libreadline.so.8). Re-extract to rebuild
                // them with guest-correct targets.
                if (bootstrapReady && !symlinkManifest.isFile()) {
                    Log.d(TAG, "Prefix has no SYMLINKS.txt (legacy install); "
                        + "re-extracting bootstrap to repair symlinks");
                    bootstrapReady = false;
                }

                if (!bootstrapReady) {
                    String url = overrideUrl != null && !overrideUrl.isEmpty()
                        ? overrideUrl : DEFAULT_BOOTSTRAP_URL;
                    post(listener, () -> listener.onProgress("Downloading bootstrap...", 5));
                    File cacheZip = new File(appContext.getCacheDir(), "bootstrap-aarch64.zip");
                    download(url, cacheZip, listener);
                    post(listener, () -> listener.onProgress("Extracting…", 70));
                    extract(cacheZip, prefixDir);
                    processSymlinks(prefixDir);
                    cacheZip.delete();
                    makeExecutable(bash);
                } else {
                    Log.d(TAG, "Bootstrap already extracted, repairing runtime libraries only");
                    post(listener, () -> listener.onProgress("Linux runtime already extracted…", 75));
                    // Idempotent: no-op once SYMLINKS.txt has been consumed.
                    processSymlinks(prefixDir);
                    if (!bash.canExecute()) makeExecutable(bash);
                }

                post(listener, () -> listener.onProgress("Installing runtime libraries…", 85));
                installRuntimeLibraries(prefixDir, listener);

                EmbeddedRuntime.invalidateReadyCache();
                installing = false;
                post(listener, () -> listener.onComplete());
            } catch (Exception e) {
                Log.e(TAG, "Prefix install failed", e);
                installing = false;
                post(listener, () -> listener.onError("Prefix install failed: " + e.getMessage()
                    + "\nSee logcat for full stack trace (TAG=PrefixInstaller)"));
            }
        });
    }

    /**
     * Blocking variant used by the backend right before launching proot: makes
     * sure an existing prefix has proot's dependencies. Downloads only what is
     * missing (normally nothing).
     */
    public synchronized void ensureRuntimeLibraries() throws Exception {
        File prefixDir = new File(appContext.getFilesDir(), "usr");
        prefixDir.mkdirs();
        // Cheap idempotent pass: repairs links deleted by older builds without
        // a full bootstrap download.
        processSymlinks(prefixDir);
        installRuntimeLibraries(prefixDir, null);
        EmbeddedRuntime.invalidateReadyCache();
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

    @SuppressLint("SdCardPath")
    private static void processSymlinks(File destDir) {
        File symlinks = new File(destDir, "SYMLINKS.txt");
        if (!symlinks.isFile()) return;
        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(symlinks),
                    StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        } catch (Exception e) {
            // Keep the manifest so a later run can retry.
            return;
        }
        for (String raw : lines) {
            if (raw == null || raw.trim().isEmpty()) continue;
            final String[] parts = raw.split("\u2190", 2);
            if (parts.length != 2) continue;
            final String target = parts[0].trim();
            String link = parts[1].trim();
            if (target.isEmpty() || link.isEmpty()) continue;
            if (link.startsWith("./")) link = link.substring(2);
            File linkFile = new File(destDir, link);

            String existing = readLink(linkFile);
            if (existing != null) {
                // Older builds resolved relative targets to absolute host paths
                // (/data/user/0/...), which proot cannot resolve because the
                // guest root is files/ - replace only those, keep valid links.
                if (!existing.startsWith("/data/user/")) continue;
                linkFile.delete();
            } else if (linkFile.exists()) {
                continue;
            }

            File parent = linkFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try {
                // Keep the manifest target verbatim: relative targets resolve
                // against the link's own directory both on the host and inside
                // proot, and /data/data/com.termux/... is the guest mount.
                android.system.Os.symlink(target, linkFile.getAbsolutePath());
            } catch (Exception e) {
                // Filesystems that refuse symlinks: materialise a copy instead.
                File resolved = resolveForHost(target, linkFile, destDir);
                if (resolved == null || !resolved.isFile()) continue;
                try {
                    if (!resolved.getCanonicalPath().equals(linkFile.getCanonicalPath())) {
                        copyFile(resolved, linkFile);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        // SYMLINKS.txt is intentionally kept: it lets a later run repair any
        // link that went missing without re-downloading the bootstrap.
    }

    /** Read a symlink target, or null when {@code f} is not a symlink. */
    private static String readLink(File f) {
        try {
            return android.system.Os.readlink(f.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    /** Map a guest symlink target to a real host file (fallback copy only). */
    private static File resolveForHost(String target, File linkFile, File destDir) {
        if (target.startsWith("/data/data/com.termux/files")) {
            File filesRoot = destDir.getParentFile();
            if (filesRoot != null) {
                return new File(filesRoot,
                    target.substring("/data/data/com.termux/files".length()));
            }
        }
        File t = new File(target);
        if (t.isAbsolute()) return t;
        return new File(linkFile.getParentFile(), target);
    }

    /**
     * Ensure every library in {@link #RUNTIME_LIBRARIES} exists in
     * {@code $PREFIX/lib}, downloading only the packages that are missing.
     */
    private void installRuntimeLibraries(File prefixDir, Listener listener) throws Exception {
        File libDir = new File(prefixDir, "lib");
        libDir.mkdirs();

        List<String[]> missing = new ArrayList<>();
        for (String[] lib : RUNTIME_LIBRARIES) {
            File f = new File(libDir, lib[1]);
            if (!f.isFile() || f.length() == 0) missing.add(lib);
        }
        if (missing.isEmpty()) {
            Log.d(TAG, "All runtime libraries present in " + libDir.getAbsolutePath());
            return;
        }

        post(listener, () -> listener.onProgress("Resolving runtime packages…", 86));
        final String packagesText = downloadString(PACKAGES_INDEX_URL);

        for (int i = 0; i < missing.size(); i++) {
            final String pkg = missing.get(i)[0];
            final String requiredFile = missing.get(i)[1];
            final int percent = 87 + i;

            String debFilename = findPackageFilename(packagesText, pkg);
            if (debFilename == null) {
                throw new Exception(pkg + " not found in the Termux package index");
            }
            post(listener, () -> listener.onProgress("Downloading " + pkg + "…", percent));

            String debUrl = REPO_BASE + debFilename.replaceFirst("^/+", "");
            byte[] debBytes = downloadBytes(debUrl);
            byte[] tarData = extractDataTar(debBytes);
            extractLibrary(tarData, libDir, requiredFile);

            File out = new File(libDir, requiredFile);
            if (!out.isFile() || out.length() == 0) {
                throw new Exception(requiredFile + " not found inside " + pkg + ".deb");
            }
            out.setExecutable(true, false);
            Log.d(TAG, pkg + " -> " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
        }

        // Keep a copy next to bash: some bootstraps resolve their dependency
        // from the executable's own directory first.
        File androidSupport = new File(libDir, "libandroid-support.so");
        if (androidSupport.isFile()) {
            File binDir = new File(prefixDir, "bin");
            binDir.mkdirs();
            File binCopy = new File(binDir, "libandroid-support.so");
            copyFile(androidSupport, binCopy);
            binCopy.setExecutable(true, false);
        }
    }

    /** Find the {@code Filename:} field of a package in the Packages index. */
    private static String findPackageFilename(String packagesText, String pkgName) {
        for (String block : packagesText.split("\n\n")) {
            String name = null;
            String filename = null;
            for (String line : block.split("\n")) {
                if (line.startsWith("Package: ")) {
                    name = line.substring("Package: ".length()).trim();
                } else if (line.startsWith("Filename: ")) {
                    filename = line.substring("Filename: ".length()).trim();
                }
            }
            if (pkgName.equals(name) && filename != null) {
                return filename;
            }
        }
        return null;
    }

    /** Locate the {@code data.tar.*} member of an ar (.deb) archive and decompress it. */
    private static byte[] extractDataTar(byte[] debBytes) throws Exception {
        if (debBytes.length < 8
            || !new String(debBytes, 0, 8, StandardCharsets.US_ASCII).equals("!<arch>\n")) {
            throw new Exception("Not a valid ar archive (.deb)");
        }

        int offset = 8;
        while (offset + 60 <= debBytes.length) {
            String name = readString(debBytes, offset, 16).trim();
            long fileSize;
            try {
                // ar header: name(16) mtime(12) uid(6) gid(6) mode(8) size(10) magic(2).
                // Reading 12 bytes would swallow the "`\n" magic and break the parse
                // for every member, so the scan never reaches data.tar.*.
                fileSize = Long.parseLong(readString(debBytes, offset + 48, 10).trim());
            } catch (NumberFormatException e) {
                offset += 60;
                continue;
            }
            if (name.startsWith("data.tar")) {
                if (offset + 60 + fileSize > debBytes.length) {
                    throw new Exception("data.tar extends beyond the .deb");
                }
                byte[] data = new byte[(int) fileSize];
                System.arraycopy(debBytes, offset + 60, data, 0, (int) fileSize);
                String lower = name.toLowerCase(Locale.US);
                if (lower.contains(".xz")) {
                    try (InputStream in = new org.tukaani.xz.XZInputStream(
                            new ByteArrayInputStream(data))) {
                        return readAll(in);
                    }
                } else if (lower.contains(".gz")) {
                    try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
                        return readAll(in);
                    }
                } else if (lower.contains(".bz2")) {
                    throw new Exception("bzip2-compressed .deb is not supported");
                }
                return data;
            }
            offset += 60 + fileSize;
            if (fileSize % 2 != 0) offset += 1;
        }
        throw new Exception("data.tar not found in .deb");
    }

    /**
     * Extract every tar entry belonging to {@code requiredName} (regular files
     * and symlinks, e.g. libtalloc.so -> libtalloc.so.2 -> libtalloc.so.2.5.0)
     * into {@code libDir}. Symlinks are materialised as copies because the app
     * filesystem is the only thing the guest ever reads.
     */
    private static void extractLibrary(byte[] tarData, File libDir, String requiredName)
        throws Exception {
        int soIndex = requiredName.indexOf(".so");
        if (soIndex < 0) throw new Exception("Invalid library name: " + requiredName);
        final String stem = requiredName.substring(0, soIndex + 3);

        Map<String, String> links = new LinkedHashMap<>();
        int offset = 0;
        while (offset + 512 <= tarData.length) {
            String name = readString(tarData, offset, 100);
            if (name.isEmpty()) break; // end-of-archive marker

            long fileSize;
            try {
                fileSize = Long.parseLong(
                    readString(tarData, offset + 124, 12).trim().replace("\u0000", ""), 8);
            } catch (NumberFormatException e) {
                offset += 512;
                continue;
            }
            char type = (char) (tarData[offset + 156] & 0xFF);
            String base = baseName(name);

            if (base.startsWith(stem)) {
                if (type == '0' || type == 0 || type == '7') {
                    int start = offset + 512;
                    if (start + fileSize > tarData.length) {
                        throw new Exception("Truncated tar entry " + name);
                    }
                    byte[] data = new byte[(int) fileSize];
                    System.arraycopy(tarData, start, data, 0, (int) fileSize);
                    writeFile(new File(libDir, base), data);
                } else if (type == '1' || type == '2') {
                    links.put(base, baseName(readString(tarData, offset + 157, 100)));
                }
            }

            offset += 512 + (int) (((fileSize + 511) / 512) * 512);
        }

        for (Map.Entry<String, String> link : links.entrySet()) {
            File dest = new File(libDir, link.getKey());
            if (dest.isFile() && dest.length() > 0) continue;
            File target = new File(libDir, link.getValue());
            if (target.isFile() && target.length() > 0) {
                copyFile(target, dest);
            }
        }

        // The soname proot asks for must exist even if the package only shipped
        // a versioned file plus symlinks we could not recreate.
        File required = new File(libDir, requiredName);
        if (!required.isFile() || required.length() == 0) {
            File fallback = null;
            File[] files = libDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.length() > 0 && f.getName().startsWith(requiredName)
                        && (fallback == null
                            || f.getName().length() > fallback.getName().length())) {
                        fallback = f;
                    }
                }
            }
            if (fallback != null) copyFile(fallback, required);
        }
    }

    private static void writeFile(File out, byte[] data) throws Exception {
        out.getParentFile().mkdirs();
        try (OutputStream os = new FileOutputStream(out)) {
            os.write(data);
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        try (InputStream in = new java.io.FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static String readString(byte[] data, int offset, int length) {
        int end = offset;
        int limit = Math.min(offset + length, data.length);
        while (end < limit && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Download bytes into memory. */
    private static byte[] downloadBytes(String urlStr) throws Exception {
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

    /** Read all bytes from an InputStream manually (works on API 24+). */
    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private static void makeExecutable(File bash) {
        try {
            if (bash.exists()) {
                boolean bashExec = bash.setExecutable(true, false);
                Log.d(TAG, "makeExecutable: bash=" + bash.getAbsolutePath() + " executable=" + bashExec);
                // Recursively make all files in $PREFIX/bin and $PREFIX/usr/bin executable
                // so they can run inside proot's chroot.
                File prefix = bash.getParentFile().getParentFile();
                if (prefix != null) {
                    makeExecutableRecursive(new File(prefix, "bin"));
                    makeExecutableRecursive(new File(prefix, "usr/bin"));
                    makeExecutableRecursive(new File(prefix, "usr/sbin"));
                    makeExecutableRecursive(new File(prefix, "bin/applets"));
                }
            } else {
                Log.e(TAG, "makeExecutable: bash does not exist at " + bash.getAbsolutePath());
            }
        } catch (Exception ignored) {
        }
    }

    static void makeExecutableRecursive(File dir) {
        if (dir == null || !dir.exists()) {
            Log.d(TAG, "makeExecutableRecursive: dir does not exist: " + dir);
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            Log.d(TAG, "makeExecutableRecursive: cannot list files in " + dir);
            return;
        }
        int count = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                makeExecutableRecursive(f);
            } else if (f.isFile() && !f.canExecute()) {
                boolean set = f.setExecutable(true, false);
                count++;
                if (count <= 5) {
                    Log.d(TAG, "makeExecutableRecursive: set exec on " + f.getAbsolutePath() + " result=" + set);
                }
            }
        }
        if (count > 5) {
            Log.d(TAG, "makeExecutableRecursive: set exec on " + count + " files in " + dir.getAbsolutePath());
        } else if (count > 0) {
            Log.d(TAG, "makeExecutableRecursive: all files already executable or none found in " + dir.getAbsolutePath());
        }
    }
}
