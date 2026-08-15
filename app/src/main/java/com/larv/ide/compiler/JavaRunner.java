package com.larv.ide.compiler;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

public class JavaRunner {
    private static final String TAG = "JavaRunner";
    private final Context context;

    public JavaRunner(Context context) {
        this.context = context;
    }

    public RunResult run(File dexFile, String mainClassName, String[] args) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
        
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        
        PrintStream capturedOut = new PrintStream(outputStream);
        PrintStream capturedErr = new PrintStream(errorStream);
        
        System.setOut(capturedOut);
        System.setErr(capturedErr);

        long startTime = System.currentTimeMillis();
        int exitCode = -1;
        String error = null;

        try {
            // Create class loader with the dex file
            ClassLoader classLoader = createClassLoader(dexFile);
            
            // Find and invoke main method
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
        } catch (Exception e) {
            error = "Runtime error: " + e.getMessage();
            Log.e(TAG, error, e);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            capturedOut.flush();
            capturedErr.flush();
        }

        long duration = System.currentTimeMillis() - startTime;
        
        return new RunResult(exitCode == 0, outputStream.toString(), errorStream.toString(), error, duration);
    }

    private ClassLoader createClassLoader(File dexFile) throws Exception {
        // Use PathClassLoader for Android
        File optimizedDir = new File(context.getCacheDir(), "dexopt");
        optimizedDir.mkdirs();
        
        return new dalvik.system.PathClassLoader(
            dexFile.getAbsolutePath(),
            ClassLoader.getSystemClassLoader()
        );
    }

    public static class RunResult {
        private final boolean success;
        private final String output;
        private final String errorOutput;
        private final String error;
        private final long durationMs;

        public RunResult(boolean success, String output, String errorOutput, String error, long durationMs) {
            this.success = success;
            this.output = output;
            this.errorOutput = errorOutput;
            this.error = error;
            this.durationMs = durationMs;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getOutput() {
            return output;
        }

        public String getErrorOutput() {
            return errorOutput;
        }

        public String getError() {
            return error;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public String getCombinedOutput() {
            StringBuilder sb = new StringBuilder();
            if (output != null && !output.isEmpty()) {
                sb.append(output);
            }
            if (errorOutput != null && !errorOutput.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(errorOutput);
            }
            if (error != null && !error.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("Error: ").append(error);
            }
            return sb.toString();
        }
    }
}