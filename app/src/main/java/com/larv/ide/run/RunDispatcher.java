package com.larv.ide.run;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.larv.ide.build.LarvBuildParser;
import com.larv.ide.compiler.Dexer;
import com.larv.ide.compiler.JavaCompiler;
import com.larv.ide.compiler.JavaRunner;
import com.larv.ide.compiler.JavascriptRunner;
import com.larv.ide.compiler.PythonRunner;
import com.larv.ide.model.Diagnostic;
import com.larv.ide.model.OpenFile;
import com.larv.ide.model.Project;
import com.larv.ide.model.Project;
import com.larv.ide.project.ProjectRecognizer;
import com.larv.ide.run.backend.BackendUnavailableException;
import com.larv.ide.run.backend.ExecRequest;
import com.larv.ide.run.backend.termux.TermuxCommandBackend;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

public class RunDispatcher {

    public interface Host {
        boolean isBusy();
        void setBusy(boolean busy);
        Project currentProject();
        String activeFilePath();
        List<OpenFile> openFiles();
        JavaCompiler javaCompiler();
        Dexer dexer();
        LarvBuildParser.BuildSpec loadBuildSpec();
        JavascriptRunner javascriptRunner();
        PythonRunner pythonRunner();
        JavaRunner javaRunner();
        ExecutorService executor();
        android.os.Handler typeCheckHandler();
        RunStreams openRunTerminal(String statusLine);
        void writeTerm(OutputStream out, String text);
        void closeTermStreams(OutputStream programOut, OutputStream stdinOut);
        void setStatus(String text);
        void showErrors(List<Diagnostic> diagnostics);
        void showErrorTab();
        void showPreview(String html, String baseUrl);
        SharedPreferences prefs();
        String findMainClass(List<OpenFile> files);
        void toast(String message);
        void openTermuxWizard();
        void executeTermux(ExecRequest request) throws BackendUnavailableException;
        // Embedded in-app Linux runtime (no external app).
        default void executeEmbedded(ExecRequest request) throws BackendUnavailableException {
            throw new BackendUnavailableException(
                com.larv.ide.run.backend.ExecutionBackend.SetupState.EMBEDDED_MISSING);
        }
        /**
         * Run a command as a REAL interactive Linux process: stdin stays open so
         * the user can type into the program (Scanner, input(), readline).
         * {@code stdinPrefeed} (e.g. larvbuild.json run.stdin file) is written
         * first, then the session stays open for the user.
         */
        default void runEmbeddedInteractive(ExecRequest request, byte[] stdinPrefeed)
                throws BackendUnavailableException {
            throw new BackendUnavailableException(
                com.larv.ide.run.backend.ExecutionBackend.SetupState.EMBEDDED_MISSING);
        }
        default void openEmbeddedSetup() { openTermuxWizard(); }
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
    private final TermuxCommandBackend termuxBackend;
    private com.larv.ide.run.backend.embedded.EmbeddedLinuxBackend embeddedBackend;

    public RunDispatcher(Host host, TermuxCommandBackend termuxBackend) {
        this.host = host;
        this.termuxBackend = termuxBackend;
    }

    public void setEmbeddedBackend(
            com.larv.ide.run.backend.embedded.EmbeddedLinuxBackend embedded) {
        this.embeddedBackend = embedded;
    }

    public void dispatch() {
        if (host.openFiles().isEmpty()) {
            host.toast("Open a file first");
            return;
        }
        if (host.isBusy()) return;

        LarvBuildParser.BuildSpec spec = loadBuildSpec();
        ProjectRecognizer.Detection detection = host.currentProject() != null
            ? ProjectRecognizer.detect(host.currentProject().getRootDir(), host.activeFilePath())
            : null;
        String language = detectRunLanguage(spec, detection);
        String entry = resolveEntryFile(spec, detection, language);

        boolean embeddedReady = embeddedBackend != null && embeddedBackend.isAvailable();
        boolean preferEmbedded = host.prefs().getBoolean("runViaEmbedded", true);
        boolean hasExplicitCmd = spec != null && !spec.runCommand.isEmpty();
        if (embeddedReady
            && (hasExplicitCmd || (preferEmbedded && usesEmbeddedToolchain(language)))) {
            runInEmbedded(spec, entry, language);
            return;
        }

        // Legacy external-Termux path kept dead: only used if embedded missing AND
        // user explicitly opted into legacy in prefs (default off).
        boolean termuxReady = termuxBackend != null && termuxBackend.isAvailable();
        boolean preferTermux = host.prefs().getBoolean("runViaTermux", false);
        if (termuxReady
            && (hasExplicitCmd || (preferTermux && usesTermuxToolchain(language)))) {
            runInTermux(spec, entry, language);
            return;
        }

        // C/C++ has no built-in fallback — never compile it as Java.
        if (ProjectRecognizer.CPP.equals(language)) {
            host.toast("C/C++ needs the Linux runtime — download it to Run");
            host.openEmbeddedSetup();
            return;
        }

        switch (language) {
            case ProjectRecognizer.PYTHON:
                runScriptProgram(spec, entry, true);
                break;
            case ProjectRecognizer.JAVASCRIPT:
                runScriptProgram(spec, entry, false);
                break;
            case ProjectRecognizer.HTML:
            case ProjectRecognizer.CSS:
                runWebPreview(entry);
                break;
            default:
                compileAndRunJava(spec);
                break;
        }
    }

    private static boolean usesTermuxToolchain(String language) {
        return ProjectRecognizer.JAVA.equals(language)
            || ProjectRecognizer.CPP.equals(language);
    }

    /** Languages routed to the embedded Linux runtime when READY. */
    private static boolean usesEmbeddedToolchain(String language) {
        return ProjectRecognizer.CPP.equals(language)
            || ProjectRecognizer.JAVA.equals(language);
    }

    static List<String> buildTermuxCommand(String language, String entryFileName,
                                           List<String> explicitCommand) {
        if (explicitCommand != null && !explicitCommand.isEmpty()) return explicitCommand;
        if (entryFileName == null || entryFileName.isEmpty()) return null;
        String base = entryFileName.contains(".")
            ? entryFileName.substring(0, entryFileName.lastIndexOf('.')) : entryFileName;
        List<String> cmd = new ArrayList<>();
        cmd.add("bash");
        cmd.add("-c");
        if (ProjectRecognizer.JAVA.equals(language)) {
            cmd.add("javac " + q(entryFileName) + " && java " + q(base));
            return cmd;
        }
        if (ProjectRecognizer.CPP.equals(language)) {
            cmd.add("clang++ -std=c++17 " + q(entryFileName)
                + " -o " + q(base) + " && ./" + q(base));
            return cmd;
        }
        return null;
    }

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void runInEmbedded(LarvBuildParser.BuildSpec spec, String entryPath,
                               String language) {
        host.setBusy(true);
        host.typeCheckHandler().removeCallbacksAndMessages(null);
        File workdir = host.currentProject() != null
            ? host.currentProject().getRootDir() : new File("/");
        String entryName = entryPath != null ? new File(entryPath).getName() : null;
        List<String> cmd = buildTermuxCommand(language, entryName,
            spec == null ? null : spec.runCommand);
        if (cmd == null) {
            host.toast("No entry file for " + language);
            host.setBusy(false);
            return;
        }
        byte[] prefeed = readStdinPrefeed(spec);
        if (prefeed != null) {
            host.setStatus("stdin: " + spec.stdinFile + " (you can keep typing)");
        }
        try {
            host.runEmbeddedInteractive(
                new ExecRequest(cmd, workdir.getAbsolutePath(), true), prefeed);
            host.setStatus("Running via embedded Linux: " + String.join(" ", cmd));
            host.setBusy(false);
        } catch (BackendUnavailableException ex) {
            host.setBusy(false);
            host.toast("Linux runtime not ready: " + ex.getState());
            host.openEmbeddedSetup();
        }
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

    private void runInTermux(LarvBuildParser.BuildSpec spec, String entryPath,
                             String language) {
        host.setBusy(true);
        host.typeCheckHandler().removeCallbacksAndMessages(null);
        File workdir = host.currentProject() != null
            ? host.currentProject().getRootDir() : new File("/");
        String entryName = entryPath != null ? new File(entryPath).getName() : null;
        List<String> cmd = buildTermuxCommand(language, entryName,
            spec == null ? null : spec.runCommand);
        if (cmd == null) {
            host.toast("No entry file for " + language);
            host.setBusy(false);
            return;
        }
        try {
            host.executeTermux(new ExecRequest(cmd, workdir.getAbsolutePath(), true));
            host.setStatus("Running via Termux: " + String.join(" ", cmd));
            host.setBusy(false);
        } catch (BackendUnavailableException ex) {
            host.setBusy(false);
            host.toast("Termux not ready: " + ex.getState());
            host.openTermuxWizard();
        }
    }

    private static String normalizeLanguageName(String raw) {
        switch (raw.trim().toLowerCase()) {
            case "java": return ProjectRecognizer.JAVA;
            case "python": case "py": return ProjectRecognizer.PYTHON;
            case "javascript": case "js": case "node": return ProjectRecognizer.JAVASCRIPT;
            case "html": return ProjectRecognizer.HTML;
            case "css": return ProjectRecognizer.CSS;
            default: return null;
        }
    }

    private String detectRunLanguage(LarvBuildParser.BuildSpec spec,
                                     ProjectRecognizer.Detection detection) {
        if (spec != null && spec.language != null && !spec.language.isEmpty()) {
            String normalized = normalizeLanguageName(spec.language);
            if (normalized != null) return normalized;
        }
        String activeFile = host.activeFilePath();
        if (!activeFile.isEmpty()) {
            String activeLanguage = ProjectRecognizer.languageForExtension(
                new File(activeFile).getName());
            if (activeLanguage != null) return activeLanguage;
        }
        if (detection != null && detection.primaryLanguage != null) {
            return detection.primaryLanguage;
        }
        return ProjectRecognizer.JAVA;
    }

    @Nullable
    private String resolveEntryFile(LarvBuildParser.BuildSpec spec,
                                    ProjectRecognizer.Detection detection, String language) {
        if (spec != null && spec.entry != null && !spec.entry.isEmpty()
            && host.currentProject() != null) {
            File candidate = new File(host.currentProject().getRootDir(), spec.entry);
            if (candidate.exists()) return candidate.getAbsolutePath();
        }
        String activeFile = host.activeFilePath();
        if (!activeFile.isEmpty() && language != null
            && language.equals(ProjectRecognizer.languageForExtension(
                new File(activeFile).getName()))) {
            return activeFile;
        }
        if (detection != null && detection.entryFile != null
            && language != null
            && language.equals(ProjectRecognizer.languageForExtension(
                new File(detection.entryFile).getName()))) {
            return detection.entryFile;
        }
        return null;
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

    private void runScriptProgram(LarvBuildParser.BuildSpec spec, String entryPath,
                                  boolean python) {
        host.setBusy(true);
        host.typeCheckHandler().removeCallbacksAndMessages(null);

        if (entryPath == null) {
            host.toast("No " + (python ? "Python" : "JavaScript")
                + " entry file found (main.py / index.js or the active tab)");
            host.setBusy(false);
            return;
        }
        final File entryFile = new File(entryPath);
        if (!entryFile.exists()) {
            host.toast("Entry file not found: " + entryFile.getName());
            host.setBusy(false);
            return;
        }

        final String source = readFileString(entryFile);
        RunStreams rs = host.openRunTerminal(python ? "Running Python..." : "Running JavaScript...");

        host.executor().execute(() -> {
            List<File> preloads = new ArrayList<>();
            List<File> pyDirs = new ArrayList<>();
            host.writeTerm(rs.programOut(), "\n");

            String[] programArgs = spec != null
                ? spec.runArgs.toArray(new String[0]) : new String[0];
            if (programArgs.length > 0) {
                host.writeTerm(rs.programOut(),
                    "Arguments: " + String.join(" ", programArgs) + "\n");
            }

            if (python) {
                PythonRunner.RunResult result = host.pythonRunner().run(source, pyDirs,
                    rs.programOut(), rs.programOut(), programArgs);
                if (result.error != null
                    && !"Python execution requires the native runtime module."
                        .equals(result.error)) {
                    host.writeTerm(rs.programOut(), result.error + "\n");
                }
                host.writeTerm(rs.programOut(),
                    "\nProcess finished in " + result.durationMs + " ms\n");
                host.closeTermStreams(rs.programOut(), rs.stdinOut());
                host.setBusy(false);
                host.setStatus(result.success ? "Done" : "Finished with errors");
            } else {
                JavascriptRunner.RunResult result = host.javascriptRunner().run(source, entryFile.getName(),
                    preloads, rs.programOut(), rs.programOut(), programArgs);
                if (result.error != null) {
                    host.writeTerm(rs.programOut(), result.error + "\n");
                }
                host.writeTerm(rs.programOut(),
                    "\nProcess finished in " + result.durationMs + " ms\n");
                host.closeTermStreams(rs.programOut(), rs.stdinOut());
                host.setBusy(false);
                host.setStatus(result.success ? "Done" : "Finished with errors");
            }
        });
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
            String name = new File(entryPath).getName().toLowerCase();
            if (name.endsWith(".css")) {
                cssHref = relativeToRoot(entryPath);
            } else if (name.endsWith(".htm") || name.endsWith(".html")) {
                html = readFileString(new File(entryPath));
            }
        }
        if (html == null && !activeFile.toLowerCase().endsWith(".css")) {
            String active = activeFile.toLowerCase();
            if (active.endsWith(".html") || active.endsWith(".htm")) {
                html = readFileString(new File(activeFile));
            }
        }
        if (html == null && cssHref == null) {
            File candidate = host.currentProject() != null
                ? new File(host.currentProject().getRootDir(), "index.html") : null;
            if (candidate != null && candidate.exists()) {
                html = readFileString(candidate);
            }
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

    private void compileAndRunJava(LarvBuildParser.BuildSpec buildSpec) {
        host.setBusy(true);
        host.typeCheckHandler().removeCallbacksAndMessages(null);

        RunStreams rs = host.openRunTerminal("Compiling...");

        host.executor().execute(() -> {
            List<File> dependencyJars = new ArrayList<>();
                JavaCompiler.CompilationResult compileResult = host.javaCompiler().compile(
                host.openFiles(), dependencyJars,
                host.prefs().getString("javaLevel", "16"));
            if (!compileResult.isSuccess() && compileResult.getRawOutput() != null
                && !compileResult.getRawOutput().isEmpty()) {
                host.writeTerm(rs.programOut(), compileResult.getRawOutput());
            }
            if (!compileResult.isSuccess()) {
                host.showErrors(compileResult.getDiagnostics());
                host.setBusy(false);
                host.setStatus("Compilation failed");
                host.showErrorTab();
                host.closeTermStreams(rs.programOut(), rs.stdinOut());
                return;
            }

            host.writeTerm(rs.programOut(), "Compilation successful");

            Dexer.DexResult dexResult = host.dexer().dex(
                compileResult.getClassFiles(), dependencyJars, null);
            if (!dexResult.isSuccess()) {
                host.writeTerm(rs.programOut(), "Dex error: " + dexResult.getError());
                host.setBusy(false);
                host.setStatus("Dex error");
                host.closeTermStreams(rs.programOut(), rs.stdinOut());
                return;
            }

            String mainClass = host.findMainClass(host.openFiles());
            if (mainClass == null && buildSpec != null && buildSpec.mainClass != null
                && !buildSpec.mainClass.isEmpty()) {
                mainClass = buildSpec.mainClass;
            }
            if (mainClass == null) {
                host.writeTerm(rs.programOut(), "Error: No main class found");
                host.setBusy(false);
                host.setStatus("No main class");
                host.closeTermStreams(rs.programOut(), rs.stdinOut());
                return;
            }

            host.writeTerm(rs.programOut(), "Dex successful, running " + mainClass + "...");

            String[] programArgs = buildSpec != null
                ? buildSpec.runArgs.toArray(new String[0]) : new String[0];
            if (programArgs.length > 0) {
                host.writeTerm(rs.programOut(),
                    "Arguments: " + String.join(" ", programArgs));
            }

            boolean stdinFed = pushStdinFile(rs, buildSpec);
            if (stdinFed) {
                host.writeTerm(rs.programOut(), "stdin: " + buildSpec.stdinFile);
            }

            JavaRunner.RunResult runResult = host.javaRunner().run(
                dexResult.getDexFile(), mainClass, programArgs,
                rs.programOut(), rs.programOut(), rs.stdinIn);

            if (runResult.getError() != null) {
                host.writeTerm(rs.programOut(), runResult.getError());
            }
            if (runResult.isSuccess()) {
                host.writeTerm(rs.programOut(), "Program finished (exit code 0)");
            }

            host.closeTermStreams(rs.programOut(), rs.stdinOut());
            host.setBusy(false);
            host.setStatus(runResult.isSuccess() ? "Done" : "Program finished with error");
        });
    }

    private boolean pushStdinFile(RunStreams rs, LarvBuildParser.BuildSpec spec) {
        Project project = host.currentProject();
        if (spec == null || spec.stdinFile == null || project == null) return false;
        File stdinSource = new File(project.getRootDir(), spec.stdinFile);
        if (!stdinSource.exists()) {
            host.writeTerm(rs.programOut(), "stdin file not found: " + spec.stdinFile);
            return false;
        }
        final byte[] data = readFileString(stdinSource).getBytes(StandardCharsets.UTF_8);
        Thread feeder = new Thread(() -> {
            try {
                rs.stdinOut().write(data);
                rs.stdinOut().flush();
            } catch (IOException ignored) {
            } finally {
                try {
                    rs.stdinOut().close();
                } catch (IOException ignored) {
                }
            }
        }, "stdin-feeder");
        feeder.setDaemon(true);
        feeder.start();
        return true;
    }

    static String readFileString(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }
}
