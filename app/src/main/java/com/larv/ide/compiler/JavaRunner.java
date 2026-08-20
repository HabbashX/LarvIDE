package com.larv.ide.compiler;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class JavaRunner {
    private static final String TAG = "JavaRunner";
    private final Context context;

    public JavaRunner(Context context) {
        this.context = context;
    }

    public RunResult run(File dexFile, String mainClassName, String[] args,
                         OutputStream stdout, OutputStream stderr,
                         InputStream stdin) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        InputStream originalIn = System.in;

        PrintStream streamOut = createPrintStream(stdout);
        PrintStream streamErr = createPrintStream(stderr);

        System.setOut(streamOut);
        System.setErr(streamErr);
        if (stdin != null) {
            System.setIn(stdin);
        }

        long startTime = System.currentTimeMillis();
        int exitCode = -1;
        String error = null;

        try {
            ClassLoader classLoader = createClassLoader(dexFile);
            Class<?> mainClass = classLoader.loadClass(mainClassName);
            Method mainMethod = mainClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
            exitCode = 0;
        } catch (ClassNotFoundException e) {
            error = "Main class not found: " + mainClassName;
            Log.e(TAG, error, e);
        } catch (NoSuchMethodException e) {
            error = "No main method found in " + mainClassName;
            Log.e(TAG, error, e);
        } catch (InvocationTargetException e) {
            exitCode = 1;
            Throwable cause = e.getCause();
            if (cause != null) {
                cause.printStackTrace(streamErr);
                error = "Exception in thread \"main\" " + cause.toString();
            } else {
                error = "Runtime error: " + e.getMessage();
            }
        } catch (Exception e) {
            error = "Runtime error: " + e.getMessage();
            Log.e(TAG, error, e);
        } finally {
            streamOut.flush();
            streamErr.flush();
            System.setOut(originalOut);
            System.setErr(originalErr);
            System.setIn(originalIn);
            if (stdin != null) {
                try {
                    stdin.close();
                } catch (IOException ignored) {
                }
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        return new RunResult(exitCode == 0, error, duration);
    }

    private PrintStream createPrintStream(OutputStream stream) {
        try {
            return new PrintStream(stream, true, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return new PrintStream(stream, true);
        }
    }

    private ClassLoader createClassLoader(File dexFile) throws Exception {
        File optimizedDir = new File(context.getCacheDir(), "dexopt");
        optimizedDir.mkdirs();

        return new dalvik.system.PathClassLoader(
            dexFile.getAbsolutePath(),
            ClassLoader.getSystemClassLoader()
        );
    }

    public static class RunResult {
        private final boolean success;
        private final String error;
        private final long durationMs;

        public RunResult(boolean success, String error, long durationMs) {
            this.success = success;
            this.error = error;
            this.durationMs = durationMs;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getError() {
            return error;
        }

        public long getDurationMs() {
            return durationMs;
        }
    }
}