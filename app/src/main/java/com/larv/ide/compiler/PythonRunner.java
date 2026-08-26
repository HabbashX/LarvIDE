package com.larv.ide.compiler;

import android.util.Log;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PythonRunner {

    private static final String TAG = "PyRunner";
    private Object pythonInstance;
    private boolean initialized = false;
    private String[] pendingArgs;

    public static class RunResult {
        public final boolean success;
        public final String error;
        public final long durationMs;

        RunResult(boolean success, String error, long durationMs) {
            this.success = success;
            this.error = error;
            this.durationMs = durationMs;
        }
    }

    private synchronized void tryInitialize() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> pythonClass = Class.forName("com.chaquo.python.Python");
            Method start = pythonClass.getMethod("start", Class.forName("com.chaquo.python.android.AndroidPlatform"));
            start.invoke(null, newPlatform());
            pythonInstance = pythonClass.getMethod("getInstance").invoke(null);
            Log.i(TAG, "Embedded CPython runtime detected");
        } catch (Throwable t) {
            pythonInstance = null;
        }
    }

    private Object newPlatform() throws Exception {
        Class<?> platformClass = Class.forName("com.chaquo.python.android.AndroidPlatform");
        return platformClass.getConstructor(android.content.Context.class)
            .newInstance(appContext);
    }

    private final android.content.Context appContext;

    public PythonRunner(android.content.Context appContext) {
        this.appContext = appContext != null ? appContext.getApplicationContext() : null;
    }

    public RunResult run(String source, List<File> pyPackageDirs,
                         OutputStream stdout, OutputStream stderr) {
        return run(source, pyPackageDirs, stdout, stderr, null);
    }

    public RunResult run(String source, List<File> pyPackageDirs,
                         OutputStream stdout, OutputStream stderr, String[] programArgs) {
        this.pendingArgs = programArgs;
        long start = System.currentTimeMillis();
        tryInitialize();
        if (pythonInstance == null) {
            write(stdout, "Python runtime is not bundled with this build.\n"
                + "Language support (editing, highlighting, project detection and pip-style\n"
                + "dependency caching from larvbuild.json) is active; a native CPython runtime\n"
                + "(Chaquopy) will be embedded in a future release to execute Python code.\n\n");
            return new RunResult(false,
                "Python execution requires the native runtime module.",
                System.currentTimeMillis() - start);
        }

        try {
            PyObjectHelper helper = new PyObjectHelper(pythonInstance);
            Object runnerModule = helper.call("getModule", "pyrunner");
            Object writer = new StreamSink(stderr != null ? stderr : stdout);
            StringBuilder sysPathCode = new StringBuilder("[r'''");
            if (pyPackageDirs != null) {
                for (int i = 0; i < pyPackageDirs.size(); i++) {
                    File d = pyPackageDirs.get(i);
                    if (i > 0) sysPathCode.append("''', r'''");
                    sysPathCode.append(d.getAbsolutePath());
                }
            }
            sysPathCode.append("''']");
            helper.callModule(runnerModule, "run", source, writer, sysPathCode.toString());
            return new RunResult(true, null, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new RunResult(false, e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private void write(OutputStream out, String text) {
        try {
            out.write(text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private static class PyObjectHelper {
        private final Object python;
        PyObjectHelper(Object python) { this.python = python; }
        Object call(String method, String arg) throws Exception {
            return python.getClass().getMethod(method, String.class).invoke(python, arg);
        }
        Object callModule(Object module, String method, Object... args) throws Exception {
            Class<?>[] paramTypes = new Class[args.length];
            for (int i = 0; i < args.length; i++) paramTypes[i] = Object.class;
            return module.getClass().getMethod("callAttr", String.class, Object[].class)
                .invoke(module, method, args);
        }
    }

    public static class StreamSink {
        private final OutputStream out;
        public StreamSink(OutputStream out) { this.out = out; }
        public void write(String s) {
            try {
                out.write(s.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception ignored) {
            }
        }
    }
}
