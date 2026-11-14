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

    public interface OutputSink { void onStdout(byte[] data, int len); void onStderr(byte[] data, int len); void onExit(int exitCode); }

    private final Context appContext;
    private final PrefixInstaller installer;
    private final com.larv.ide.concurrent.IOExecutor executor =
        new com.larv.ide.concurrent.IOExecutor(1, 3);

    public EmbeddedLinuxBackend(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.installer = new PrefixInstaller(appContext);
    }

    public PrefixInstaller installer() { return installer; }

    @Override
    public boolean isAvailable() { return setupState() == SetupState.READY; }

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
        try {
            executor.execute(() -> {
                try {
                    executeCapture(request, null);
                } catch (Exception e) {
                    Log.e(TAG, "execute failed", e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            throw new BackendUnavailableException(SetupState.EMBEDDED_ERROR);
        }
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

        // PROOT_LOADER tells proot to use the W^X-safe loader on Android 14+.
        File nativeDir = new File(appContext.getApplicationInfo().nativeLibraryDir);
        File prootLoader = new File(nativeDir, "libproot-loader.so");
        if (prootLoader.exists()) {
            env.put("PROOT_LOADER", prootLoader.getAbsolutePath());
        }

        Process proc = pb.start();

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
            // Make proot executable (packaged from jniLibs may lose exec bit).
            proot.setExecutable(true, false);
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
            // Spike fallback: try the Android dynamic linker first, which can
            // load an ELF from app data even when direct exec is blocked
            // (Android 14 W^X / SELinux). Falls back to direct exec otherwise.
            File linker = new File("/system/bin/linker64");
            if (linker.exists()) {
                cmd.add(linker.getAbsolutePath());
                cmd.add(bashPath);
            } else {
                cmd.add(bashPath);
            }
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

        // PROOT_LOADER tells proot to use the W^X-safe loader on Android 14+.
        File nativeDir = new File(appContext.getApplicationInfo().nativeLibraryDir);
        File prootLoader = new File(nativeDir, "libproot-loader.so");
        if (prootLoader.exists()) {
            env.put("PROOT_LOADER", prootLoader.getAbsolutePath());
        }

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
        File prefix = new File(appContext.getFilesDir(), "usr");
        String bashPath = EmbeddedRuntime.bashFile(appContext).getAbsolutePath();
        String homePath = new File(appContext.getFilesDir(), "home").getAbsolutePath();

        if (proot.exists()) {
            cmd.add(proot.getAbsolutePath());
            // chroot into the prefix so /bin/bash is the prefix bash.
            cmd.add("-r");
            cmd.add(prefix.getAbsolutePath());
            // Make host filesystems visible inside the prefix.
            cmd.add("-b");
            cmd.add("/dev");
            cmd.add("-b");
            cmd.add("/proc");
            File sys = new File("/sys");
            if (sys.isDirectory()) {
                cmd.add("-b");
                cmd.add("/sys");
            }
            File sdcard = android.os.Environment.getExternalStorageDirectory();
            if (sdcard != null && sdcard.isDirectory()) {
                cmd.add("-b");
                cmd.add(sdcard.getAbsolutePath());
            }
            cmd.add("-b");
            cmd.add(appContext.getFilesDir().getAbsolutePath());
            cmd.add("-b");
            cmd.add(appContext.getCacheDir().getAbsolutePath() + ":/tmp");
            cmd.add("-b");
            cmd.add(homePath + ":/home");
            // Start in the project folder when it is reachable inside the prefix.
            if (workdir != null) {
                cmd.add("-w");
                cmd.add(workdir);
            } else {
                cmd.add("-w");
                cmd.add("/home");
            }
            cmd.add(bashPath);
            cmd.add("-l");
        } else {
            // Spike fallback for devices without proot: try the dynamic linker.
            File linker = new File("/system/bin/linker64");
            if (linker.exists()) {
                cmd.add(linker.getAbsolutePath());
            }
            cmd.add(bashPath);
            cmd.add("-l");
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workdir != null && !proot.exists()) {
            pb.directory(new File(workdir));
        }
        java.util.Map<String, String> env = pb.environment();
        env.put("PREFIX", "/");
        env.put("HOME", "/home");
        env.put("TERM", "xterm-256color");
        env.put("PATH", "/bin:/usr/bin:/usr/local/bin:/system/bin:/system/xbin");
        // LD_LIBRARY_PATH so the dynamic linker finds libandroid-support.so.
        // Inside proot chroot, /lib = $PREFIX/lib on host. Outside proot (linker64 fallback),
        // use the host path. Include both so either works.
        env.put("LD_LIBRARY_PATH", "/lib:" + prefix.getAbsolutePath() + "/lib");

        // PROOT_LOADER tells proot to use the W^X-safe loader on Android 14+.
        File nativeDir2 = new File(appContext.getApplicationInfo().nativeLibraryDir);
        File prootLoader = new File(nativeDir2, "libproot-loader.so");
        if (prootLoader.exists()) {
            env.put("PROOT_LOADER", prootLoader.getAbsolutePath());
        }

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
