package com.larv.ide.run.backend.embedded;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.larv.ide.run.backend.BackendUnavailableException;
import com.larv.ide.run.backend.ExecRequest;
import com.larv.ide.run.backend.ExecutionBackend;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app Linux execution: Termux bootstrap in private storage + proot.
 * Never opens an external app. Replaces the legacy RUN_COMMAND path.
 *
 * <p>Requires {@code libproot.so} + {@code libproot-loader.so} in
 * {@code jniLibs/arm64-v8a} (PROOT_LOADER W^X bypass, see oonid/pr pattern)
 * and a downloaded prefix ({@link PrefixInstaller}).
 */
public class EmbeddedLinuxBackend implements ExecutionBackend {

    private static final String TAG = "EmbeddedLinux";

    public interface OutputSink {
        void onStdout(byte[] data, int len);
        void onStderr(byte[] data, int len);
        void onExit(int exitCode);
    }

    private final Context appContext;
    private final PrefixInstaller installer;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public EmbeddedLinuxBackend(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.installer = new PrefixInstaller(appContext);
    }

    public PrefixInstaller installer() {
        return installer;
    }

    @Override
    public boolean isAvailable() {
        return setupState() == SetupState.READY;
    }

    @Override
    public SetupState setupState() {
        if (installer.isInstalling()) return SetupState.EMBEDDED_INSTALLING;
        if (EmbeddedRuntime.isEmbeddedReady(appContext)) return SetupState.READY;
        PrefixStatus s = installer.status();
        if (s == PrefixStatus.ERROR) return SetupState.EMBEDDED_ERROR;
        return SetupState.EMBEDDED_MISSING;
    }

    @Override
    public void execute(ExecRequest request) throws BackendUnavailableException {
        SetupState state = setupState();
        if (state != SetupState.READY) {
            throw new BackendUnavailableException(state);
        }
        // Fire-and-forget variant used by legacy callers: run in background,
        // output goes nowhere (callers wanting output use executeCapture()).
        executor.execute(() -> {
            try {
                executeCapture(request, null);
            } catch (Exception e) {
                Log.e(TAG, "execute failed", e);
            }
        });
    }

    /** Run command inside the prefix, streaming output to sink. Returns exit code. */
    public int executeCapture(ExecRequest request, OutputSink sink) throws Exception {
        List<String> prootCmd = buildProotCommand(request);
        ProcessBuilder pb = new ProcessBuilder(prootCmd);
        if (request.getWorkdir() != null) {
            pb.directory(new File(request.getWorkdir()));
        }
        pb.redirectErrorStream(false);
        java.util.Map<String, String> env = pb.environment();
        File prefix = new File(appContext.getFilesDir(), "usr");
        env.put("PREFIX", prefix.getAbsolutePath());
        env.put("HOME", new File(appContext.getFilesDir(), "home").getAbsolutePath());
        env.put("TERM", "xterm-256color");
        env.put("PATH", prefix.getAbsolutePath() + "/bin:" + prefix.getAbsolutePath() + "/bin/applets");

        Process proc = pb.start();
        // Feed /dev/null stdin for non-interactive; interactive callers drive stdin themselves.
        try {
            proc.getOutputStream().close();
        } catch (Exception ignored) {
        }

        Thread outPump = pump(proc.getInputStream(), true, sink);
        Thread errPump = pump(proc.getErrorStream(), false, sink);
        int exit = proc.waitFor();
        outPump.join(5_000);
        errPump.join(5_000);
        if (sink != null) sink.onExit(exit);
        return exit;
    }

    /** Build: [libproot.so, --bind..., bash, -c, "<cmd>"] or direct bash if no proot yet (spike). */
    List<String> buildProotCommand(ExecRequest request) {
        File nativeDir = new File(appContext.getApplicationInfo().nativeLibraryDir);
        File proot = new File(nativeDir, "libproot.so");
        String bashPath = EmbeddedRuntime.bashFile(appContext).getAbsolutePath();
        String shellCmd = joinShell(request.getCommand());

        List<String> cmd = new ArrayList<>();
        if (proot.exists()) {
            cmd.add(proot.getAbsolutePath());
            // Bind shared workspace so Monaco + compiler see the same files.
            File sdcard = android.os.Environment.getExternalStorageDirectory();
            File workspace = new File(sdcard, "LarvIDE/projects");
            if (workspace.isDirectory()) {
                cmd.add("--bind=" + workspace.getAbsolutePath() + ":" + workspace.getAbsolutePath());
            }
            // Build outputs to prefix tmp to dodge FUSE slowness.
            cmd.add("--bind=" + appContext.getCacheDir().getAbsolutePath() + ":/tmp");
            cmd.add(bashPath);
            cmd.add("-c");
            cmd.add(shellCmd);
        } else {
            // Spike fallback: direct bash exec (works on rooted/older devices,
            // fails with EACCES under W^X on targetSdk 34 — signals proot is required).
            cmd.add(bashPath);
            cmd.add("-c");
            cmd.add(shellCmd);
        }
        return cmd;
    }

    private static String joinShell(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static Thread pump(InputStream in, boolean stdout, OutputSink sink) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (sink != null) {
                        if (stdout) sink.onStdout(buf, n);
                        else sink.onStderr(buf, n);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }, stdout ? "emb-out" : "emb-err");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Interactive command process for Run-with-input: stdin is LEFT OPEN so the
     * user can type into Scanner / input() / readline while the program runs.
     * stderr is merged into stdout (single terminal stream, like a real PTY).
     * The caller owns the process lifetime and must close its streams.
     */
    public Process openCommandProcess(ExecRequest request) throws Exception {
        SetupState state = setupState();
        if (state != SetupState.READY) {
            throw new BackendUnavailableException(state);
        }
        List<String> prootCmd = buildProotCommand(request);
        ProcessBuilder pb = new ProcessBuilder(prootCmd);
        if (request.getWorkdir() != null) {
            pb.directory(new File(request.getWorkdir()));
        }
        java.util.Map<String, String> env = pb.environment();
        File prefix = new File(appContext.getFilesDir(), "usr");
        env.put("PREFIX", prefix.getAbsolutePath());
        env.put("HOME", new File(appContext.getFilesDir(), "home").getAbsolutePath());
        env.put("TERM", "xterm-256color");
        env.put("PATH", prefix.getAbsolutePath() + "/bin:" + prefix.getAbsolutePath() + "/bin/applets");
        pb.redirectErrorStream(true);
        // NOTE: stdin intentionally left open — the terminal view drives it.
        return pb.start();
    }

    /** Interactive shell process for the embedded terminal view. */
    public Process openShell(String workdir) throws Exception {
        SetupState state = setupState();
        if (state != SetupState.READY) {
            throw new BackendUnavailableException(state);
        }
        List<String> cmd = new ArrayList<>();
        File nativeDir = new File(appContext.getApplicationInfo().nativeLibraryDir);
        File proot = new File(nativeDir, "libproot.so");
        String bashPath = EmbeddedRuntime.bashFile(appContext).getAbsolutePath();
        if (proot.exists()) {
            cmd.add(proot.getAbsolutePath());
            cmd.add(bashPath);
        } else {
            cmd.add(bashPath);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workdir != null) pb.directory(new File(workdir));
        java.util.Map<String, String> env = pb.environment();
        File prefix = new File(appContext.getFilesDir(), "usr");
        env.put("PREFIX", prefix.getAbsolutePath());
        env.put("TERM", "xterm-256color");
        env.put("PATH", prefix.getAbsolutePath() + "/bin:/system/bin:/system/xbin");
        pb.redirectErrorStream(true);
        return pb.start();
    }

    public static void copyTo(OutputStream out, byte[] data, int len) {
        if (out == null) return;
        try {
            out.write(data, 0, len);
            out.flush();
        } catch (Exception ignored) {
        }
    }
}
