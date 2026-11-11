package com.larv.ide.run;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.larv.ide.build.LarvBuildParser;
import com.larv.ide.model.OpenFile;
import com.larv.ide.model.Project;
import com.larv.ide.project.ProjectRecognizer;
import com.larv.ide.run.backend.ExecRequest;
import com.larv.ide.run.backend.BackendUnavailableException;
import com.larv.ide.run.backend.embedded.EmbeddedLinuxBackend;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Single execution path: every runnable language is compiled/executed by
 * toolchains installed in the embedded Linux prefix. There are no in-app
 * language runtimes and no on-device dexing — Java runs on the prefix JRE
 * (javac + java), Python on python3, JavaScript on node, C/C++ on clang.
 *
 * <p>Before anything runs, {@link RunEligibility} verifies that the file is a
 * runnable type <em>and</em> contains a real entry point.</p>
 */
public class RunDispatcher {

    public interface Host {
        boolean isBusy();
        void setBusy(boolean busy);
        Project currentProject();
        String activeFilePath();
        LarvBuildParser.BuildSpec loadBuildSpec();
        android.os.Handler typeCheckHandler();
        void setStatus(String text);
        void showPreview(String html, String baseUrl);
        SharedPreferences prefs();
        void toast(String message);
        void openEmbeddedSetup();
        /**
         * Run a command as a REAL interactive Linux process: stdin stays open so
         * the user can type into the program (Scanner, input(), readline).
         * {@code stdinPrefeed} (e.g. larvbuild.json run.stdin file) is written
         * first, then the session stays open for the user.
         */
        void runEmbeddedInteractive(ExecRequest request, byte[] stdinPrefeed)
                throws BackendUnavailableException;
    }

    public static class RunStreams {
        public final PipedInputStream programIn = new PipedInputStream(64 * 1024);
        public final PipedOutputStream programOut = new PipedOutputStream();
        public final PipedInputStream stdinIn = new PipedInputStream(64 * 1024);
        public final PipedOutputStream stdinOut = new PipedOutputStream();

        public InputStream programIn() { return programIn; }
        public OutputStream programOut() { return programOut; }
        public OutputStream stdinOut() { return stdinOut; }
    }

    private final Host host;
    private EmbeddedLinuxBackend embeddedBackend;

    public RunDispatcher(Host host) {
        this.host = host;
    }

    public void setEmbeddedBackend(EmbeddedLinuxBackend embedded) {
        this.embeddedBackend = embedded;
    }

    public void dispatch() {
        if (host.isBusy()) return;

        LarvBuildParser.BuildSpec spec = loadBuildSpec();
        String activePath = host.activeFilePath();
        String language = detectLanguage(spec, activePath);
        boolean explicitCommand = spec != null && !spec.runCommand.isEmpty();

        if (language == null && !explicitCommand) {
            String activeName = activePath == null || activePath.isEmpty()
                ? null : new File(activePath).getName();
            host.toast(activeName == null
                ? "Open a runnable file first (.java, .py, .js, .c, .cpp)"
                : activeName + " is not a runnable file type");
            host.setStatus(activeName == null ? "No runnable file"
                : activeName + " · not runnable");
            return;
        }

        String entry = resolveEntry(spec, activePath, language);
        if (language != null && (ProjectRecognizer.HTML.equals(language)
                || ProjectRecognizer.CSS.equals(language))) {
            runWebPreview(entry);
            return;
        }
        if (explicitCommand) {
            runInEmbedded(spec, entry, language);
            return;
        }
        if (entry == null) {
            host.toast("No entry file for " + language);
            host.setStatus("No entry file for " + language);
            return;
        }

        RunEligibility.Verdict verdict =
            RunEligibility.check(new File(entry).getName(), readFileString(new File(entry)));
        if (!verdict.runnable) {
            // A helper class can be the active tab while the project still has
            // a real entry point (Main.java). Run that instead of dead-ending.
            String fallback = ProjectRecognizer.JAVA.equals(language)
                ? findJavaEntry(entry) : null;
            if (fallback != null) {
                String activeName = activePath == null || activePath.isEmpty()
                    ? new File(entry).getName() : new File(activePath).getName();
                host.setStatus(activeName + " has no main — running "
                    + new File(fallback).getName());
                entry = fallback;
            } else {
                String name = new File(entry).getName();
                host.toast(name + ": " + verdict.reason);
                host.setStatus(name + " · " + verdict.reason);
                return;
            }
        }

        runInEmbedded(spec, entry, language);
    }

    /**
     * Active tab wins: if the user is looking at notes.txt, Run must not fall
     * back to the project's Java file. Project detection is only used when no
     * file is open.
     */
    private String detectLanguage(LarvBuildParser.BuildSpec spec,
                                  String activePath) {
        if (activePath != null && !activePath.isEmpty()) {
            return ProjectRecognizer.languageForExtension(
                new File(activePath).getName());
        }
        if (spec != null && spec.language != null && !spec.language.isEmpty()) {
            String normalized = normalizeLanguageName(spec.language);
            if (normalized != null) return normalized;
        }
        Project project = host.currentProject();
        if (project != null) {
            ProjectRecognizer.Detection detection =
                ProjectRecognizer.detect(project.getRootDir(), null);
            return detection.primaryLanguage;
        }
        return null;
    }

    @Nullable
    private String resolveEntry(LarvBuildParser.BuildSpec spec, String activePath,
                                String language) {
        Project project = host.currentProject();
        if (spec != null && spec.entry != null && !spec.entry.isEmpty()
            && project != null) {
            File candidate = new File(project.getRootDir(), spec.entry);
            if (candidate.exists()) return candidate.getAbsolutePath();
        }
        if (activePath != null && !activePath.isEmpty() && language != null
            && language.equals(ProjectRecognizer.languageForExtension(
                new File(activePath).getName()))) {
            return activePath;
        }
        if (project != null && language != null) {
            ProjectRecognizer.Detection detection =
                ProjectRecognizer.detect(project.getRootDir(), activePath);
            if (detection.entryFile != null
                && language.equals(ProjectRecognizer.languageForExtension(
                    new File(detection.entryFile).getName()))) {
                return detection.entryFile;
            }
        }
        return null;
    }

    /**
     * Bounded scan for another Java file that actually has a main method.
     * Prefers {@code Main.java}, then the shallowest match.
     */
    @Nullable
    private String findJavaEntry(String activeEntry) {
        Project project = host.currentProject();
        File root = project != null ? project.getRootDir()
            : activeEntry != null ? new File(activeEntry).getParentFile() : null;
        if (root == null) return null;
        return findJavaEntry(root, 0, activeEntry);
    }

    @Nullable
    private String findJavaEntry(File dir, int depth, String skip) {
        if (depth > 5 || dir == null) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        List<File> dirs = new ArrayList<>();
        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                dirs.add(f);
                continue;
            }
            if (!f.getName().endsWith(".java")) continue;
            if (skip != null && f.getAbsolutePath().equals(skip)) continue;
            if ("Main.java".equals(f.getName())) return f.getAbsolutePath();
            RunEligibility.Verdict v =
                RunEligibility.check(f.getName(), readFileString(f));
            if (v.runnable) return f.getAbsolutePath();
        }
        for (File d : dirs) {
            String found = findJavaEntry(d, depth + 1, skip);
            if (found != null) return found;
        }
        return null;
    }

    private static String normalizeLanguageName(String raw) {
        switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "java": return ProjectRecognizer.JAVA;            case "python": case "py": return ProjectRecognizer.PYTHON;
            case "javascript": case "js": case "node": return ProjectRecognizer.JAVASCRIPT;
            case "html": return ProjectRecognizer.HTML;
            case "css": return ProjectRecognizer.CSS;
            case "c": case "cpp": case "c++": return ProjectRecognizer.CPP;
            default: return null;
        }
    }

    private void runInEmbedded(LarvBuildParser.BuildSpec spec, String entryPath,
                               String language) {
        boolean explicit = spec != null && !spec.runCommand.isEmpty();
        File entryFile = entryPath != null ? new File(entryPath) : null;
        if (!explicit && entryFile == null) {
            host.toast("No entry file for " + language);
            return;
        }

        host.setBusy(true);
        host.typeCheckHandler().removeCallbacksAndMessages(null);

        Project project = host.currentProject();
        File root = project != null ? project.getRootDir()
            : entryFile != null && entryFile.getParentFile() != null
                ? entryFile.getParentFile() : new File("/");
        // Java compiles a package tree, so it runs from the project root.
        // Single-file languages run from the file's own directory.
        File workdir = ProjectRecognizer.JAVA.equals(language)
            ? root : entryFile != null && entryFile.getParentFile() != null
                ? entryFile.getParentFile() : root;

        List<String> cmd;
        try {
            if (explicit) {
                cmd = spec.runCommand;
            } else {
                cmd = buildCommand(language, entryFile, workdir,
                    spec != null ? spec.runArgs : null);
            }
        } catch (IllegalArgumentException ex) {
            host.setBusy(false);
            host.toast(ex.getMessage());
            host.setStatus(ex.getMessage());
            return;
        }
        if (cmd == null) {
            host.setBusy(false);
            host.toast("This file type isn't runnable");
            return;
        }

        byte[] prefeed = readStdinPrefeed(spec);
        if (prefeed != null) {
            host.setStatus("stdin: " + spec.stdinFile + " (you can keep typing)");
        }
        try {
            host.runEmbeddedInteractive(
                new ExecRequest(cmd, workdir.getAbsolutePath(), true), prefeed);
            host.setStatus("Running " + language + " via Linux: "
                + String.join(" ", cmd));
            host.setBusy(false);
        } catch (BackendUnavailableException ex) {
            host.setBusy(false);
            host.toast("Linux runtime not ready: " + ex.getState());
            host.openEmbeddedSetup();
        }
    }

    private List<String> buildCommand(String language, File entryFile, File workdir,
                                      @Nullable List<String> runArgs) {
        String entryName = entryFile.getName();
        String content = readFileString(entryFile);
        RunEligibility.Verdict verdict = RunEligibility.check(entryName, content);
        if (!verdict.runnable) {
            throw new IllegalArgumentException(entryName + ": " + verdict.reason);
        }

        if (ProjectRecognizer.JAVA.equals(language)) {
            File classesDir = new File(workdir, ".larv-classes");
            //noinspection ResultOfMethodCallIgnored
            classesDir.mkdirs();
            String release = host.prefs().getString("javaLevel", null);
            return buildJavaCommand(workdir.getAbsolutePath(),
                classesDir.getAbsolutePath(), verdict.mainClass, release);
        }
        if (ProjectRecognizer.CPP.equals(language)) {
            return buildCppCommand(entryName, runArgs);
        }
        if (ProjectRecognizer.PYTHON.equals(language)) {
            return buildScriptCommand("python3", entryName, runArgs);
        }
        if (ProjectRecognizer.JAVASCRIPT.equals(language)) {
            return buildScriptCommand("node", entryName, runArgs);
        }
        throw new IllegalArgumentException(language + " isn't runnable");
    }

    /**
     * javac compiles every .java under the source root (packages included) into
     * {@code .larv-classes}; the prefix JRE then executes the fully-qualified
     * main class. No dexing, no ART.
     */
    static List<String> buildJavaCommand(String sourceDir, String classesDir,
                                         String mainClass, @Nullable String release) {
        if (mainClass == null) {
            throw new IllegalArgumentException("No main class found");
        }
        StringBuilder shell = new StringBuilder();
        shell.append("javac -encoding UTF-8");
        if (release != null && !release.trim().isEmpty()) {
            shell.append(" --release ").append(q(release.trim()));
        }
        shell.append(" -d ").append(q(classesDir));
        shell.append(" $(find ").append(q(sourceDir))
            .append(" -path '*/.*' -prune -o -name '*.java' -print)");
        shell.append(" && java -cp ").append(q(classesDir))
            .append(' ').append(q(mainClass));
        return Arrays.asList("bash", "-c", shell.toString());
    }

    static List<String> buildCppCommand(String entryName, @Nullable List<String> args) {
        String base = baseName(entryName);
        StringBuilder shell = new StringBuilder();
        shell.append("clang++ -std=c++17 ").append(q(entryName))
            .append(" -o ").append(q(base))
            .append(" && ./").append(q(base));
        appendArgs(shell, args);
        return Arrays.asList("bash", "-c", shell.toString());
    }

    static List<String> buildScriptCommand(String interpreter, String entryName,
                                           @Nullable List<String> args) {
        StringBuilder shell = new StringBuilder();
        shell.append(interpreter).append(' ').append(q(entryName));
        appendArgs(shell, args);
        return Arrays.asList("bash", "-c", shell.toString());
    }

    private static void appendArgs(StringBuilder shell, @Nullable List<String> args) {
        if (args == null) return;
        for (String a : args) shell.append(' ').append(q(a));
    }

    private static String baseName(String name) {
        return name.contains(".")
            ? name.substring(0, name.lastIndexOf('.')) : name;
    }

    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Raw bytes of the larvbuild.json run.stdin file, or null. */
    @Nullable
    private byte[] readStdinPrefeed(LarvBuildParser.BuildSpec spec) {
        Project project = host.currentProject();
        if (spec == null || spec.stdinFile == null || project == null) return null;
        File stdinSource = new File(project.getRootDir(), spec.stdinFile);
        if (!stdinSource.exists()) {
            host.toast("stdin file not found: " + spec.stdinFile);
            return null;
        }
        try (FileInputStream fis = new FileInputStream(stdinSource)) {
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private void runWebPreview(@Nullable String entryPath) {
        host.setBusy(true);
        String baseUrl = host.currentProject() != null
            ? "file://" + host.currentProject().getRootDir().getAbsolutePath() + "/"
            : "about:blank";
        String activeFile = host.activeFilePath();

        String html = null;
        String cssHref = null;
        if (entryPath != null && new File(entryPath).exists()) {
            String name = new File(entryPath).getName().toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".css")) {
                cssHref = relativeToRoot(entryPath);
            } else if (name.endsWith(".htm") || name.endsWith(".html")) {
                html = readFileString(new File(entryPath));
            }
        }
        if (html == null && activeFile != null
            && !activeFile.toLowerCase(java.util.Locale.ROOT).endsWith(".css")) {
            String active = activeFile.toLowerCase(java.util.Locale.ROOT);
            if (active.endsWith(".html") || active.endsWith(".htm")) {
                html = readFileString(new File(activeFile));
            }
        }
        if (html == null && cssHref == null && host.currentProject() != null) {
            File candidate = new File(host.currentProject().getRootDir(), "index.html");
            if (candidate.exists()) html = readFileString(candidate);
        }
        if (html == null && cssHref == null) {
            host.toast("No HTML/CSS file found to preview");
            host.setBusy(false);
            return;
        }

        if (html == null) {
            html = "<!DOCTYPE html>\n<html>\n<head>\n"
                + "<link rel=\"stylesheet\" href=\"" + cssHref + "\">\n"
                + "</head>\n<body>\n"
                + "<h1>CSS Preview</h1>\n"
                + "<p>This page is rendered with your stylesheet applied.</p>\n"
                + "<button>Button sample</button>\n"
                + "</body>\n</html>\n";
        }

        host.showPreview(html, baseUrl);
        host.setBusy(false);
    }

    @Nullable
    private String relativeToRoot(String path) {
        Project project = host.currentProject();
        if (project == null) return new File(path).getName();
        String root = project.getRootDir().getAbsolutePath();
        return path.startsWith(root + File.separator)
            ? path.substring(root.length() + 1) : new File(path).getName();
    }

    @Nullable
    public LarvBuildParser.BuildSpec loadBuildSpec() {
        Project project = host.currentProject();
        if (project == null) return null;
        for (String name : new String[]{"larvbuild.json", "larv.json"}) {
            File f = new File(project.getRootDir(), name);
            if (f.exists()) {
                try {
                    return LarvBuildParser.readBuildSpec(f);
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    static String readFileString(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            // Decode once: the old chunk-per-8192-bytes loop allocated a String
            // per read and could split a multi-byte character in half.
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                (int) Math.min(Math.max(file.length(), 64), 64 * 1024));
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }
}
