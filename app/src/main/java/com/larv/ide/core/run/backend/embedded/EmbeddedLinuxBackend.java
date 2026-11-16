package com.larv.ide.core.run.backend.embedded;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.larv.ide.core.concurrent.IOExecutor;
import com.larv.ide.core.run.backend.BackendUnavailableException;
import com.larv.ide.core.run.backend.ExecRequest;
import com.larv.ide.core.run.backend.ExecutionBackend;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * In-app Linux execution: Termux bootstrap in private storage + proot.
 * Never opens an external app. Replaces the legacy RUN_COMMAND path.
 *
 * <p>Android blocks {@code exec()} of files inside an app's data directory, so
 * the prefix binaries can only run through proot: proot loads the guest ELF
 * itself and maps it, which bypasses the exec restriction. For that to work
 * both proot and its loader must be extracted to
 * {@code jniLibs/arm64-v8a} (see {@code useLegacyPackaging = true} in
 * {@code app/build.gradle}) and the prefix must be mounted at the path Termux
 * packages hard-code, {@code /data/data/com.termux/files/usr}.
 */
public class EmbeddedLinuxBackend implements ExecutionBackend {

    private static final String TAG = "EmbeddedLinux";

    /** Prefix path burned into Termux shebangs ({@code bin/pkg}) and ELF RUNPATHs. */
    @SuppressLint("SdCardPath")
    static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    /** Home path Termux packages expect. */
    @SuppressLint("SdCardPath")
    static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    public interface OutputSink { void onStdout(byte[] data, int len); void onStderr(byte[] data, int len); void onExit(int exitCode); }

    private final Context appContext;
    private final PrefixInstaller installer;
    private final IOExecutor executor =
        new IOExecutor(1, 3);

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
        ensureRuntimeLibraries();
        List<String> prootCmd = buildProotCommand(request);
        ProcessBuilder pb = new ProcessBuilder(prootCmd);
        if (request.getWorkdir() != null) {
            pb.directory(new File(request.getWorkdir()));
        }
        pb.redirectErrorStream(false);
        configureEnvironment(pb);

        Process proc = pb.start();

        Thread outPump = pump(proc.getInputStream(), true, sink);
        Thread errPump = pump(proc.getErrorStream(), false, sink);
        int exit = proc.waitFor();
        outPump.join(5_000);
        errPump.join(5_000);
        if (sink != null) sink.onExit(exit);
        return exit;
    }

    /**
     * Build: [proot, --kill-on-exit, -r <files>, -b ..., -w <cwd>, <caller argv...>].
     * Every command goes through proot: running prefix binaries directly (or via
     * linker64) is denied with EACCES by the Android exec restriction.
     *
     * <p>The caller's argv is passed through verbatim. Do not join it into a
     * string and re-wrap it in another {@code bash -c}: that re-parse drops the
     * quoting of the original script, so {@code bash -c "mkdir -p '/x y'"}
     * arrives as {@code bash -c mkdir -p '/x y'} and mkdir fails with
     * "missing operand". Callers that need a shell already ask for
     * {@code ["bash", "-c", script]} themselves.
     */
    List<String> buildProotCommand(ExecRequest request) {
        return prootCommand(request.getWorkdir(), resolveProgram(request.getCommand()));
    }

    /**
     * Map a bare program name ({@code pkg}, {@code javac}, {@code python3}) to
     * its absolute guest path inside the prefix, so proot never has to guess.
     * Absolute paths and unknown names are passed through unchanged.
     */
    private List<String> resolveProgram(List<String> command) {
        String name = command.get(0);
        if (name.indexOf('/') >= 0) return command;
        File prefix = EmbeddedRuntime.prefixDir(appContext);
        for (String dir : new String[]{"bin", "bin/applets"}) {
            if (new File(prefix, dir + File.separator + name).isFile()) {
                List<String> resolved = new ArrayList<>(command);
                resolved.set(0, TERMUX_PREFIX + "/" + dir + "/" + name);
                return resolved;
            }
        }
        return command;
    }

    /**
     * Single source of truth for proot invocations. Mirrors Termux's
     * {@code termux-chroot}: root at the app files dir, the prefix mounted both
     * as a normal {@code /usr} (plus {@code /bin}, {@code /lib}, ...) and at the
     * {@code /data/data/com.termux/files/usr} path packages hard-code.
     */
    private List<String> prootCommand(String workdir, List<String> program) {
        File proot = requireProot();
        File prefix = EmbeddedRuntime.prefixDir(appContext);
        File filesRoot = appContext.getFilesDir();
        File home = EmbeddedRuntime.homeDir(appContext);
        File cache = appContext.getCacheDir();
        File prootTmp = new File(cache, "proot");
        prootTmp.mkdirs();

        List<String> cmd = new ArrayList<>();
        cmd.add(proot.getAbsolutePath());
        // Do not leave orphaned processes behind when the IDE stops a program.
        cmd.add("--kill-on-exit");

        // Rootfs: <filesDir> so guest /usr == the downloaded prefix.
        cmd.add("-r");
        cmd.add(filesRoot.getAbsolutePath());

        // Classic Linux hierarchy inside the prefix (/tmp is bound to the cache
        // dir below, so it is intentionally left out here).
        cmd.add("-b");
        cmd.add(prefix.getAbsolutePath() + ":/usr");
        for (String dir : new String[]{"bin", "etc", "lib", "share", "var"}) {
            File d = new File(prefix, dir);
            if (d.isDirectory()) {
                cmd.add("-b");
                cmd.add(d.getAbsolutePath() + ":/" + dir);
            }
        }

        // Hard-coded Termux paths: pkg's shebang and apt/javac RUNPATHs point here.
        cmd.add("-b");
        cmd.add(prefix.getAbsolutePath() + ":" + TERMUX_PREFIX);
        cmd.add("-b");
        cmd.add(home.getAbsolutePath() + ":" + TERMUX_HOME);
        cmd.add("-b");
        cmd.add(home.getAbsolutePath() + ":/home");

        // Android runtime bits needed by the guest dynamic linker / toolchains.
        for (String dir : new String[]{"/system", "/vendor", "/odm", "/product",
                "/apex", "/dev", "/proc", "/sys", "/storage"}) {
            if (new File(dir).exists()) {
                cmd.add("-b");
                cmd.add(dir + ":" + dir);
            }
        }
        for (String file : new String[]{"/linkerconfig/ld.config.txt", "/property_contexts"}) {
            if (new File(file).exists()) {
                cmd.add("-b");
                cmd.add(file + ":" + file);
            }
        }

        // Writable /tmp on the cache dir (FUSE-backed /sdcard is too slow here).
        cmd.add("-b");
        cmd.add(cache.getAbsolutePath() + ":/tmp");

        // Projects live on shared storage, already covered by /storage above;
        // bind explicitly in case the ROM does not expose /storage.
        File workspace = new File(android.os.Environment.getExternalStorageDirectory(),
            "LarvIDE/projects");
        if (workspace.isDirectory()) {
            cmd.add("-b");
            cmd.add(workspace.getAbsolutePath() + ":" + workspace.getAbsolutePath());
        }

        cmd.add("-w");
        cmd.add(guestPath(workdir));
        cmd.addAll(program);
        Log.d(TAG, "proot command: " + cmd);
        return cmd;
    }

    /**
     * Translate a host working directory to the path the guest sees. Paths under
     * shared storage keep their absolute path (they are bound 1:1); paths under
     * the app files dir are rebased on the proot rootfs.
     */
    private String guestPath(String workdir) {
        if (workdir == null) return TERMUX_HOME;
        File prefix = EmbeddedRuntime.prefixDir(appContext);
        File filesRoot = appContext.getFilesDir();
        String path = new File(workdir).getAbsolutePath();
        String prefixPath = prefix.getAbsolutePath();
        if (path.equals(prefixPath)) return TERMUX_PREFIX;
        if (path.startsWith(prefixPath + File.separator)) {
            return TERMUX_PREFIX + path.substring(prefixPath.length()).replace(File.separatorChar, '/');
        }
        String filesPath = filesRoot.getAbsolutePath();
        if (path.equals(filesPath)) return "/";
        if (path.startsWith(filesPath + File.separator)) {
            return path.substring(filesPath.length()).replace(File.separatorChar, '/');
        }
        return path.replace(File.separatorChar, '/');
    }

    private File requireProot() {
        File nativeDir = new File(appContext.getApplicationInfo().nativeLibraryDir);
        File proot = new File(nativeDir, "libproot.so");
        if (!proot.isFile()) {
            throw new IllegalStateException(
                "Embedded runtime is incomplete: " + proot.getAbsolutePath()
                    + " is missing. The APK must package jniLibs with"
                    + " useLegacyPackaging=true so proot is extracted on install.");
        }
        // lib/ is an immutable APK data dir, so setExecutable() is denied by
        // SELinux and only logs an avc warning; it is already r-x. Ask only
        // when the file really is not executable.
        if (!proot.canExecute()) proot.setExecutable(true, false);
        if (!proot.canExecute()) {
            throw new IllegalStateException("proot is not executable: " + proot.getAbsolutePath());
        }
        return proot;
    }

    /**
     * Environment shared by proot itself and by the guest programs. The host
     * needs {@code LD_LIBRARY_PATH} so the dynamic linker finds libtalloc /
     * libandroid-shmem for proot; the guest values mirror a real Termux install.
     */
    private void configureEnvironment(ProcessBuilder pb) {
        File prefix = EmbeddedRuntime.prefixDir(appContext);
        File cache = appContext.getCacheDir();
        File prootTmp = new File(cache, "proot");
        prootTmp.mkdirs();
        EmbeddedRuntime.homeDir(appContext);

        Map<String, String> env = pb.environment();
        // --- guest environment (Termux conventions) ---
        env.put("PREFIX", TERMUX_PREFIX);
        env.put("HOME", TERMUX_HOME);
        env.put("SHELL", TERMUX_PREFIX + "/bin/bash");
        env.put("PATH", TERMUX_PREFIX + "/bin:" + TERMUX_PREFIX + "/bin/applets"
            + ":/usr/bin:/bin:/system/bin:/system/xbin");
        env.put("TMPDIR", "/tmp");
        env.put("TERM", "xterm-256color");
        env.put("LANG", "en_US.UTF-8");

        // --- host-side entries used by proot and its dependencies ---
        // proot's DT_RUNPATH points at Termux's prefix; that path does not exist
        // on this device, so point the linker at our copy of the libraries.
        env.put("LD_LIBRARY_PATH", "/usr/lib:/lib:" + prefix.getAbsolutePath() + "/lib");
        env.put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
        File loader = new File(new File(appContext.getApplicationInfo().nativeLibraryDir),
            "libproot-loader.so");
        if (loader.isFile()) {
            if (!loader.canExecute()) loader.setExecutable(true, false);
            env.put("PROOT_LOADER", loader.getAbsolutePath());
        } else {
            Log.e(TAG, "PROOT_LOADER missing at " + loader.getAbsolutePath()
                + " — proot will not be able to map guest executables");
        }
    }

    /** Install proot's own shared-library dependencies if an old prefix lacks them. */
    private void ensureRuntimeLibraries() throws Exception {
        if (EmbeddedRuntime.hasRuntimeLibraries(appContext)) return;
        Log.d(TAG, "Runtime libraries missing, installing proot dependencies");
        installer.ensureRuntimeLibraries();
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
        ensureRuntimeLibraries();
        List<String> prootCmd = buildProotCommand(request);
        ProcessBuilder pb = new ProcessBuilder(prootCmd);
        if (request.getWorkdir() != null) {
            pb.directory(new File(request.getWorkdir()));
        }
        configureEnvironment(pb);
        pb.redirectErrorStream(true);
        // NOTE: stdin intentionally left open — the terminal view drives it.

        makePrefixBinariesExecutable();

        Log.d(TAG, "openCommandProcess: workdir="
            + (request.getWorkdir() != null ? request.getWorkdir() : "null"));
        Process p = pb.start();
        Log.d(TAG, "openCommandProcess: process started");
        return p;
    }

    /** Interactive shell process for the embedded terminal view. */
    public Process openShell(String workdir) throws Exception {
        SetupState state = setupState();
        if (state != SetupState.READY) {
            throw new BackendUnavailableException(state);
        }
        // The terminal is opened from the UI thread: never start a download here.
        if (!EmbeddedRuntime.hasRuntimeLibraries(appContext)) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                throw new IllegalStateException(
                    "Linux runtime is incomplete — run the setup from Settings first");
            }
            ensureRuntimeLibraries();
        }
        List<String> cmd = prootCommand(workdir, Arrays.asList("/bin/bash", "-l"));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        configureEnvironment(pb);
        pb.redirectErrorStream(true);

        makePrefixBinariesExecutable();

        Log.d(TAG, "openShell: starting process: " + cmd);
        Process p = pb.start();
        Log.d(TAG, "openShell: process started");
        return p;
    }

    /**
     * The bootstrap zip does not carry Unix mode bits, and packages installed
     * later by dpkg may land without +x. Inside proot the kernel permission
     * check is gone, but bash still refuses to exec a non-executable file, so
     * keep the PATH dirs marked +x.
     */
    private void makePrefixBinariesExecutable() {
        File prefix = EmbeddedRuntime.prefixDir(appContext);
        File binDir = new File(prefix, "bin");
        if (binDir.exists()) {
            PrefixInstaller.makeExecutableRecursive(binDir);
        }
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
