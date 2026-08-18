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
import java.nio.charset.Charset;

public class JavaRunner {
    private static final String TAG = "JavaRunner";
    private final Context context;

    public interface LineListener {
        void onLine(String line);
    }

    public JavaRunner(Context context) {
        this.context = context;
    }

    public RunResult run(File dexFile, String mainClassName, String[] args,
                         LineListener outListener, LineListener errListener,
                         InputStream stdin) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        InputStream originalIn = System.in;

        PrintStream streamOut = new PrintStream(
            new LineOutputStream(outListener), true, Charset.forName("UTF-8"));
        PrintStream streamErr = new PrintStream(
            new LineOutputStream(errListener), true, Charset.forName("UTF-8"));

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

    private ClassLoader createClassLoader(File dexFile) throws Exception {
        File optimizedDir = new File(context.getCacheDir(), "dexopt");
        optimizedDir.mkdirs();

        return new dalvik.system.PathClassLoader(
            dexFile.getAbsolutePath(),
            ClassLoader.getSystemClassLoader()
        );
    }

    private static class LineOutputStream extends OutputStream {
        private final LineListener listener;
        private final StringBuilder buffer = new StringBuilder();

        LineOutputStream(LineListener listener) {
            this.listener = listener;
        }

        @Override
        public void write(int b) {
            if (b == '\n') {
                emit();
            } else if (b != '\r') {
                buffer.append((char) b);
            }
        }

        @Override
        public void flush() {
            emit();
        }

        private void emit() {
            if (buffer.length() > 0) {
                String line = buffer.toString();
                buffer.setLength(0);
                if (listener != null) {
                    listener.onLine(line);
                }
            }
        }
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