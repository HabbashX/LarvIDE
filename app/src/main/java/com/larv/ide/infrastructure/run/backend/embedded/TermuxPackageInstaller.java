package com.larv.ide.infrastructure.run.backend.embedded;

import android.content.Context;
import android.util.Log;

import com.larv.ide.infrastructure.run.backend.ExecRequest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Installs Termux packages without going through apt's https method.
 *
 * <p>Some Android 10 / MIUI devices crash inside apt's native https method
 * ("Method https has died unexpectedly", "Sub-process https ... signal 4"),
 * which makes {@code pkg install} unusable even though plain HTTPS from the
 * app works. This class uses the same Java HTTP stack that downloaded the
 * bootstrap: it reads the Termux Packages index, walks dependencies, downloads
 * the .deb files and installs them with {@code dpkg -i} inside proot. apt is
 * never started, so neither its TLS library nor its mirror list is involved.
 */
public final class TermuxPackageInstaller {

    private static final String TAG = "PkgDownload";

    public interface Listener {
        void onLog(String line);
    }

    private final Context appContext;
    private final EmbeddedLinuxBackend backend;

    public TermuxPackageInstaller(Context context, EmbeddedLinuxBackend backend) {
        this.appContext = context.getApplicationContext();
        this.backend = backend;
    }

    /** @return dpkg's exit code (0 on success). */
    public int install(List<String> packageNames, Listener listener) throws Exception {
        log(listener, "Fetching Termux package index…");
        String index = PrefixInstaller.downloadString(PrefixInstaller.packagesIndexUrl());
        Map<String, Pkg> available = parseIndex(index);
        if (available.isEmpty()) throw new Exception("Empty Termux package index");

        Set<String> installed = installedPackages();
        LinkedHashSet<Pkg> plan = new LinkedHashSet<>();
        for (String name : packageNames) {
            collect(name.trim(), available, installed, plan, new HashSet<>(), listener, 0);
        }
        if (plan.isEmpty()) {
            boolean allInstalled = true;
            for (String name : packageNames) {
                if (!installed.contains(stripConstraint(name))) allInstalled = false;
            }
            if (allInstalled) {
                log(listener, "Already installed: " + String.join(", ", packageNames));
                return 0;
            }
            throw new Exception("No installable packages found for "
                + String.join(", ", packageNames));
        }

        File debDir = new File(appContext.getCacheDir(), "debs");
        if (!debDir.isDirectory() && !debDir.mkdirs()) {
            throw new Exception("Cannot create " + debDir.getAbsolutePath());
        }
        List<String> guestPaths = new ArrayList<>();
        for (Pkg pkg : plan) {
            String fileName = baseName(pkg.filename);
            File local = new File(debDir, fileName);
            if (!local.isFile() || local.length() == 0) {
                log(listener, "Downloading " + pkg.name + " (" + pkg.version + ")…");
                PrefixInstaller.downloadToFile(
                    PrefixInstaller.repoBase() + pkg.filename.replaceFirst("^/+", ""), local);
            } else {
                log(listener, "Cached " + fileName);
            }
            // The app cache dir is mounted as /tmp inside proot.
            guestPaths.add("/tmp/debs/" + fileName);
        }

        StringBuilder script = new StringBuilder("dpkg -i");
        for (String path : guestPaths) script.append(" '").append(path).append('\'');
        log(listener, "Installing " + plan.size() + " package(s) with dpkg…");
        int exit = backend.executeCapture(
            new ExecRequest(Arrays.asList("bash", "-c", script.toString()), null, false),
            new EmbeddedLinuxBackend.OutputSink() {
                @Override public void onStdout(byte[] data, int len) {
                    log(listener, new String(data, 0, len, StandardCharsets.UTF_8));
                }
                @Override public void onStderr(byte[] data, int len) {
                    log(listener, new String(data, 0, len, StandardCharsets.UTF_8));
                }
                @Override public void onExit(int code) { }
            });
        if (exit != 0) {
            log(listener, "dpkg reported errors; trying to finish configuration…");
            int configure = backend.executeCapture(
                new ExecRequest(Arrays.asList("bash", "-c", "dpkg --configure -a"), null, false),
                new EmbeddedLinuxBackend.OutputSink() {
                    @Override public void onStdout(byte[] data, int len) {
                        log(listener, new String(data, 0, len, StandardCharsets.UTF_8));
                    }
                    @Override public void onStderr(byte[] data, int len) {
                        log(listener, new String(data, 0, len, StandardCharsets.UTF_8));
                    }
                    @Override public void onExit(int code) { }
                });
            if (configure == 0) exit = 0;
        }
        Log.d(TAG, "install(" + packageNames + ") -> " + exit);
        return exit;
    }

    /** Recursively add dependencies before their dependants (post-order). */
    private static void collect(String rawName, Map<String, Pkg> available,
                                Set<String> installed, LinkedHashSet<Pkg> plan,
                                Set<String> visiting, Listener listener, int depth) {
        String name = stripConstraint(rawName);
        if (name.isEmpty() || installed.contains(name)) return;
        if (plan.contains(new Pkg(name, null, null, null))) return;
        if (depth > 24 || !visiting.add(name)) return;

        Pkg pkg = available.get(name);
        if (pkg == null) {
            // Virtual packages and packages shipped by the ROM have no entry in
            // the index; dpkg will report a real problem if one was required.
            log(listener, "Skipping unavailable dependency " + name);
            visiting.remove(name);
            return;
        }
        for (String dep : splitDependencies(pkg.depends)) {
            for (String alternative : dep.split("\\|")) {
                String candidate = stripConstraint(alternative);
                if (candidate.isEmpty()) continue;
                if (installed.contains(candidate)) break;
                if (available.containsKey(candidate)) {
                    collect(candidate, available, installed, plan, visiting, listener, depth + 1);
                    break;
                }
            }
        }
        visiting.remove(name);
        plan.add(pkg);
    }

    /** Parse the Debian Packages index into name -> metadata. */
    static Map<String, Pkg> parseIndex(String text) {
        Map<String, Pkg> result = new HashMap<>();
        for (String block : text.split("\\r?\\n\\r?\\n")) {
            String name = null, version = null, filename = null, depends = null;
            for (String line : block.split("\\r?\\n")) {
                if (line.startsWith("Package:")) name = value(line);
                else if (line.startsWith("Version:")) version = value(line);
                else if (line.startsWith("Filename:")) filename = value(line);
                else if (line.startsWith("Depends:")) depends = value(line);
                else if (line.startsWith("Pre-Depends:")) {
                    String pre = value(line);
                    depends = depends == null || depends.isEmpty()
                        ? pre : depends + ", " + pre;
                }
            }
            if (name != null && !name.isEmpty() && filename != null && !filename.isEmpty()) {
                result.put(name, new Pkg(name, version, filename, depends));
            }
        }
        return result;
    }

    /** Names dpkg already considers installed. */
    private Set<String> installedPackages() {
        Set<String> result = new HashSet<>();
        File status = new File(EmbeddedRuntime.prefixDir(appContext),
            "var/lib/dpkg/status");
        if (!status.isFile()) return result;
        try {
            String text = new String(PrefixInstaller.readAllBytes(status), StandardCharsets.UTF_8);
            for (String block : text.split("\\r?\\n\\r?\\n")) {
                String name = null;
                boolean installed = false;
                for (String line : block.split("\\r?\\n")) {
                    if (line.startsWith("Package:")) name = value(line);
                    else if (line.startsWith("Status:") && line.contains("installed")) {
                        installed = true;
                    }
                }
                if (installed && name != null) result.add(name);
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot read dpkg status", e);
        }
        return result;
    }

    private static String value(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? "" : line.substring(colon + 1).trim();
    }

    private static String stripConstraint(String dep) {
        int paren = dep.indexOf('(');
        String name = paren < 0 ? dep : dep.substring(0, paren);
        return name.replaceAll("[^a-zA-Z0-9+.-].*$", "").trim();
    }

    private static List<String> splitDependencies(String depends) {
        List<String> result = new ArrayList<>();
        if (depends == null || depends.trim().isEmpty()) return result;
        for (String part : depends.split(",")) {
            if (!part.trim().isEmpty()) result.add(part.trim());
        }
        return result;
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static void log(Listener listener, String text) {
        if (listener == null || text == null || text.isEmpty()) return;
        listener.onLog(text.endsWith("\n") ? text : text + "\n");
    }

    /** Value object for one index entry; identity is the package name. */
    static final class Pkg {
        final String name;
        final String version;
        final String filename;
        final String depends;

        Pkg(String name, String version, String filename, String depends) {
            this.name = name;
            this.version = version;
            this.filename = filename;
            this.depends = depends;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Pkg && name.equals(((Pkg) other).name);
        }

        @Override public int hashCode() {
            return name.hashCode();
        }
    }
}
