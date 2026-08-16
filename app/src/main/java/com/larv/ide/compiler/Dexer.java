package com.larv.ide.compiler;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public class Dexer {
    private static final String TAG = "Dexer";
    private final Context context;
    private final File outputDir;

    public Dexer(@NonNull Context context) {
        this.context = context;
        this.outputDir = new File(context.getCacheDir(), "dexoutput");
        this.outputDir.mkdirs();
    }

    @SuppressLint("NewApi")
    public DexResult dex(List<File> classFiles, File outputDir) {
        File dexOutputDir = outputDir != null ? outputDir : this.outputDir;
        dexOutputDir.mkdirs();

        try {
            D8Command.Builder builder = D8Command.builder();
            builder.setMode(CompilationMode.DEBUG);
            builder.setOutput(dexOutputDir.toPath(), OutputMode.DexIndexed);
            builder.setMinApiLevel(21);
            for (File classFile : classFiles) {
                builder.addProgramFiles(classFile.toPath());
            }

            D8.run(builder.build());

            File classesDex = new File(dexOutputDir, "classes.dex");
            if (classesDex.exists()) {
                return new DexResult(true, classesDex, null);
            } else {
                return new DexResult(false, null, "No classes.dex generated");
            }
        } catch (Exception e) {
            Log.w(TAG, "D8 failed, using fallback: " + e.getMessage(), e);
        }

        // Fallback: create a simple JAR with class files for ClassLoader
        File jarFile = new File(dexOutputDir, "classes.jar");
        try {
            createJarFromClassFiles(classFiles, jarFile);
        } catch (IOException e) {
            Log.e(TAG, "Failed to create JAR", e);
            return new DexResult(false, null, "Failed to create JAR: " + e.getMessage());
        }

        if (jarFile.exists()) {
            return new DexResult(true, jarFile, null);
        } else {
            return new DexResult(false, null, "Failed to create JAR from class files");
        }
    }

    private void createJarFromClassFiles(List<File> classFiles, File jarFile) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(new java.io.FileOutputStream(jarFile))) {

            for (File classFile : classFiles) {
                if (classFile.isDirectory()) {
                    addDirectoryToJar(jos, classFile, classFile, "");
                } else if (classFile.getName().endsWith(".class")) {
                    addFileToJar(jos, classFile, "");
                }
            }
        }
    }

    private void addDirectoryToJar(JarOutputStream jos, File root, File dir, String prefix)
            throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            String entryName = prefix + file.getName();
            if (file.isDirectory()) {
                addDirectoryToJar(jos, root, file, entryName + "/");
            } else if (file.getName().endsWith(".class")) {
                addFileToJar(jos, file, prefix);
            }
        }
    }

    private void addFileToJar(JarOutputStream jos, File file, String prefix)
            throws IOException {
        String entryName = prefix + file.getName();
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            JarEntry entry = new JarEntry(entryName);
            jos.putNextEntry(entry);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                jos.write(buffer, 0, len);
            }
            jos.closeEntry();
        }
    }

    public static class DexResult {
        private final boolean success;
        private final File dexFile;
        private final String error;

        public DexResult(boolean success, File dexFile, String error) {
            this.success = success;
            this.dexFile = dexFile;
            this.error = error;
        }

        public boolean isSuccess() {
            return success;
        }

        public File getDexFile() {
            return dexFile;
        }

        public String getError() {
            return error;
        }
    }
}