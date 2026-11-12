package com.larv.ide.activity;

import com.larv.ide.R;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import com.larv.ide.build.LarvBuildParser;
import com.larv.ide.model.Diagnostic;
import com.larv.ide.model.Project;
import com.larv.ide.run.RunDispatcher;
import com.larv.ide.run.RunEligibility;
import com.larv.ide.ui.TerminalActivity;
import com.larv.ide.ui.fragment.OutputFragment;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns everything that happens between pressing Run and the program exiting:
 * run-eligibility checks, the dispatcher host, the terminal bridge, compiler
 * diagnostics and the Stop button. MainActivity only wires it up.
 */
final class RunController {

    private static final long RUN_STATE_DEBOUNCE_MS = 250;
    private static final long RUN_FLUSH_DELAY_MS = 250;

    private final MainActivity activity;
    private final Handler runStateHandler = new Handler(Looper.getMainLooper());
    private final Handler typeCheckHandler = new Handler(Looper.getMainLooper());
    private final RunDispatcher dispatcher;

    private volatile int runStateToken = 0;
    private volatile boolean compiling = false;
    private volatile Process runningProcess;
    private boolean runQueued = false;

    RunController(MainActivity activity) {
        this.activity = activity;
        this.dispatcher = new RunDispatcher(new RunHost());
    }

    void setEmbeddedBackend(com.larv.ide.run.backend.embedded.EmbeddedLinuxBackend backend) {
        dispatcher.setEmbeddedBackend(backend);
    }

    Handler typeCheckHandler() {
        return typeCheckHandler;
    }

    boolean isCompiling() {
        return compiling;
    }

    /** Immediate eligibility check (open/tab switch). */
    void updateRunActionState(String filePath, String content) {
        // An immediate check supersedes any pending typing check.
        runStateToken++;
        runStateHandler.removeCallbacksAndMessages(null);
        if (filePath == null || filePath.isEmpty()) {
            setRunEnabled(false);
            return;
        }
        String name = new File(filePath).getName();
        RunEligibility.Verdict verdict = RunEligibility.check(name, content);
        setRunEnabled(verdict.runnable);
        if (filePath.equals(activity.currentEditorFile)) {
            activity.statusText.setText(verdict.statusLabel(name));
        }
    }

    /**
     * Typing variant: coalesces keystrokes (one check per pause) and parses
     * the file off the main thread. A stale result never wins - the token is
     * bumped on every edit and on destroy.
     */
    void scheduleRunActionState(String filePath, String content) {
        if (filePath == null || filePath.isEmpty()) {
            runStateToken++;
            runStateHandler.removeCallbacksAndMessages(null);
            setRunEnabled(false);
            return;
        }
        final int token = ++runStateToken;
        runStateHandler.removeCallbacksAndMessages(null);
        runStateHandler.postDelayed(() -> {
            if (token != runStateToken) return;
            activity.compilerExecutor.execute(() -> {
                String name = new File(filePath).getName();
                RunEligibility.Verdict verdict = RunEligibility.check(name, content);
                activity.runOnUiThread(() -> {
                    if (token != runStateToken) return;
                    setRunEnabled(verdict.runnable);
                    if (filePath.equals(activity.currentEditorFile)) {
                        activity.statusText.setText(verdict.statusLabel(name));
                    }
                });
            });
        }, RUN_STATE_DEBOUNCE_MS);
    }

    void setRunEnabled(boolean enabled) {
        if (activity.btnRun == null) return;
        activity.btnRun.setEnabled(enabled);
        activity.btnRun.setAlpha(enabled ? 1f : 0.35f);
    }

    void compileAndRun() {
        if (compiling || runQueued) return;
        runQueued = true;
        // 1. Push Monaco's debounced edits into OpenFile (RAM) NOW-
        if (activity.editorFragment != null) {
            activity.editorFragment.flushContent();
        }
        // 2. -then, after the JS-bridge round trip lands (~250ms), flush the
        // fresh RAM to disk (embedded javac/clang compile files on disk) and run.
        // The compiler never sees the "old Main" again.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            runQueued = false;
            // Flush on the compilation pool so StrictMode (debug builds) does not
            // flag disk I/O on the main thread.
            activity.compilerExecutor.execute(() -> {
                activity.saveAllModifiedFilesSyncQuiet();
                activity.sessionManager.saveNowSync();
                dispatcher.dispatch();
            });
        }, RUN_FLUSH_DELAY_MS);
    }

    void stopRunningProcess() {
        Process p = runningProcess;
        if (p == null) {
            Toast.makeText(activity, "No running process", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            p.destroy();
        } catch (Exception ignored) {
        }
        runningProcess = null;
        activity.runOnUiThread(() -> activity.statusText.setText("Stopped"));
    }

    void cancelPendingChecks() {
        runStateToken++;
        runStateHandler.removeCallbacksAndMessages(null);
        typeCheckHandler.removeCallbacksAndMessages(null);
    }

    private final class RunHost implements RunDispatcher.Host {
        @Override public boolean isBusy() { return compiling; }
        @Override public void setBusy(boolean busy) { compiling = busy; }
        @Override public Project currentProject() { return activity.currentProject; }
        @Override public String activeFilePath() { return activity.currentEditorFile; }
        @Override public LarvBuildParser.BuildSpec loadBuildSpec() {
            if (activity.currentProject == null) return null;
            for (String name : new String[]{"larvbuild.json", "larv.json"}) {
                File f = new File(activity.currentProject.getRootDir(), name);
                if (f.exists()) {
                    try {
                        return LarvBuildParser.readBuildSpec(f);
                    } catch (Exception ignored) {
                    }
                }
            }
            return null;
        }
        @Override public Handler typeCheckHandler() { return typeCheckHandler; }
        @Override public void setStatus(String text) {
            activity.runOnUiThread(() -> activity.statusText.setText(text));
        }
        @Override public void showPreview(String html, String baseUrl) {
            activity.runOnUiThread(() -> {
                activity.bottomPanelAdapter.getPreviewFragment().showHtml(html, baseUrl);
                activity.bottomWindowVisible = true;
                activity.bottomToolWindow.setVisibility(View.VISIBLE);
                activity.bottomResizer.setVisibility(View.VISIBLE);
                activity.bottomViewPager.setCurrentItem(2);
                activity.statusText.setText("Preview updated");
            });
        }
        @Override public android.content.SharedPreferences prefs() { return activity.prefs; }
        @Override public void runEmbeddedInteractive(com.larv.ide.run.backend.ExecRequest request,
                byte[] stdinPrefeed)
                throws com.larv.ide.run.backend.BackendUnavailableException {
            // REAL Linux session: process stdin stays open, terminal keystrokes
            // flow straight into the program (Scanner / input() / readline).
            com.larv.ide.run.backend.embedded.EmbeddedLinuxBackend backend =
                activity.embeddedBackend;
            if (backend == null || !backend.isAvailable()) {
                throw new com.larv.ide.run.backend.BackendUnavailableException(
                    backend != null ? backend.setupState()
                        : com.larv.ide.run.backend.ExecutionBackend.SetupState.EMBEDDED_MISSING);
            }
            try {
                Process proc = backend.openCommandProcess(request);
                attachExternalProcess(proc,
                    "$ " + String.join(" ", request.getCommand()) + "\n", stdinPrefeed);
            } catch (com.larv.ide.run.backend.BackendUnavailableException e) {
                throw e;
            } catch (Exception e) {
                toast("Embedded run failed: " + e.getMessage());
            }
        }
        @Override public void openEmbeddedSetup() {
            activity.runOnUiThread(() -> activity.showEmbeddedSetupDialog());
        }
        @Override public void toast(String message) {
            activity.runOnUiThread(() -> Toast.makeText(activity, message,
                Toast.LENGTH_SHORT).show());
        }
    }

    RunDispatcher.RunStreams openRunTerminal(String statusLine) {
        RunDispatcher.RunStreams rs = new RunDispatcher.RunStreams();
        try {
            rs.programOut.connect(rs.programIn);
            rs.stdinOut.connect(rs.stdinIn);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create program pipes", e);
        }
        OutputFragment outputFragment = activity.bottomPanelAdapter.getOutputFragment();
        activity.runOnUiThread(() -> {
            activity.statusText.setText(statusLine);
            outputFragment.clear();
            outputFragment.startProgram(rs.programIn, rs.stdinOut);
            activity.bottomPanelAdapter.getErrorsFragment().setErrors(new ArrayList<>());
            activity.bottomViewPager.setCurrentItem(0);
            activity.bottomWindowVisible = true;
            activity.bottomToolWindow.setVisibility(View.VISIBLE);
            activity.bottomResizer.setVisibility(View.VISIBLE);
        });
        return rs;
    }

    /**
     * Attach a REAL external process to the Run tab: the terminal view reads the
     * process stdout and the soft keyboard writes straight to the process stdin.
     * No manual capture - the session is live until the process exits.
     */
    private void attachExternalProcess(Process proc, String banner, byte[] stdinPrefeed) {
        runningProcess = proc;
        try {
            java.io.PipedInputStream sessionIn = new java.io.PipedInputStream(64 * 1024);
            java.io.PipedOutputStream bridge = new java.io.PipedOutputStream(sessionIn);
            OutputStream procIn = proc.getOutputStream();
            java.io.InputStream procOut = proc.getInputStream();
            if (banner != null) {
                bridge.write(banner.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
                bridge.flush();
            }
            if (stdinPrefeed != null && stdinPrefeed.length > 0) {
                procIn.write(stdinPrefeed);
                procIn.flush();
            }
            OutputFragment outputFragment = activity.bottomPanelAdapter.getOutputFragment();
            activity.runOnUiThread(() -> {
                activity.statusText.setText("Running via embedded Linux- (type into the terminal)");
                outputFragment.clear();
                outputFragment.startProgram(sessionIn, procIn);
                activity.bottomWindowVisible = true;
                activity.bottomToolWindow.setVisibility(View.VISIBLE);
                activity.bottomResizer.setVisibility(View.VISIBLE);
                activity.bottomViewPager.setCurrentItem(0);
            });
            Thread watcher = new Thread(() -> {
                byte[] buf = new byte[8192];
                int exit = -1;
                StringBuilder output = new StringBuilder();
                try {
                    int n;
                    while ((n = procOut.read(buf)) > 0) {
                        String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                        output.append(chunk);
                        bridge.write(buf, 0, n);
                        bridge.flush();
                    }
                } catch (IOException ignored) {
                }
                try {
                    exit = proc.waitFor();
                } catch (InterruptedException ignored) {
                }
                try {
                    String msg = "\r\nProcess finished with exit code " + exit + "\r\n";
                    bridge.write(msg.getBytes(StandardCharsets.UTF_8));
                    bridge.flush();
                    bridge.close();
                } catch (IOException ignored) {
                }
                try {
                    procOut.close();
                } catch (IOException ignored) {
                }
                final int code = exit;
                if (runningProcess == proc) runningProcess = null;
                List<Diagnostic> diagnostics = parseCompilerDiagnostics(output.toString());
                activity.runOnUiThread(() -> {
                    activity.statusText.setText(code == 0 ? "Done" : "Finished with errors");
                    if (!diagnostics.isEmpty()) {
                        activity.bottomPanelAdapter.getErrorsFragment().setErrors(
                            new ArrayList<>(diagnostics));
                        activity.bottomViewPager.setCurrentItem(1);
                    }
                });
            }, "emb-watch");
            watcher.setDaemon(true);
            watcher.start();
        } catch (IOException e) {
            Toast.makeText(activity, "Embedded run failed: " + e.getMessage(),
                Toast.LENGTH_SHORT).show();
        }
    }

    void writeTerm(OutputStream out, String text) {
        if (out == null || text == null) return;
        try {
            out.write(text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    void closeTermStreams(OutputStream programOutputOut, OutputStream programInputOut) {
        try {
            programOutputOut.close();
        } catch (IOException ignored) {
        }
        try {
            programInputOut.close();
        } catch (IOException ignored) {
        }
    }

    List<Diagnostic> parseCompilerDiagnostics(String output) {
        List<Diagnostic> result = new ArrayList<>();
        if (output == null || output.isEmpty()) return result;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "(.+?):(\\d+)(?::(\\d+))?:\\s*(error|warning):\\s*(.+)");
        String root = activity.currentProject != null
            ? activity.currentProject.getRootDir().getAbsolutePath() : null;
        for (String rawLine : output.split("\\r?\\n")) {
            java.util.regex.Matcher m = p.matcher(rawLine);
            if (!m.find()) continue;
            String file = m.group(1).trim();
            int line = 0;
            try { line = Integer.parseInt(m.group(2)); } catch (NumberFormatException ignored) { }
            String sev = m.group(4).toLowerCase(java.util.Locale.ROOT);
            Diagnostic.Severity severity = "warning".equals(sev)
                ? Diagnostic.Severity.WARNING
                : Diagnostic.Severity.ERROR;
            if (!new File(file).isAbsolute() && root != null) {
                file = new File(root, file).getAbsolutePath();
            }
            result.add(new Diagnostic(file, line, 0, m.group(5).trim(), severity));
        }
        return result;
    }

    void openFullscreenTerminal() {
        File workdir = activity.currentProject != null
            ? activity.currentProject.getRootDir() : activity.getFilesDir();
        Intent intent = new Intent(activity, TerminalActivity.class);
        intent.putExtra(TerminalActivity.EXTRA_WORKDIR, workdir.getAbsolutePath());
        activity.startActivity(intent);
    }

    void openEmbeddedTerminal() {
        File workdir = activity.currentProject != null
            ? activity.currentProject.getRootDir() : activity.getFilesDir();
        activity.bottomPanelAdapter.getTerminalFragment().openSession(workdir);
        activity.bottomViewPager.setCurrentItem(3);
        activity.bottomWindowVisible = true;
        activity.bottomToolWindow.setVisibility(View.VISIBLE);
        activity.bottomResizer.setVisibility(View.VISIBLE);
    }
}
