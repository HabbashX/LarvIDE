package com.larv.ide.compiler;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class Dexer {
    private static final String TAG = "Dexer";
    private final Context context;
    private final File outputDir;

    public Dexer(@NonNull Context context) {
        this.context = context;
        this.outputDir = new File(context.getCacheDir(), "dexoutput");
        this.outputDir.mkdirs();
    }

    public DexResult dex(List<File> classFiles, File outputDir) {
        File dexOutputDir = outputDir != null ? outputDir : this.outputDir;
        dexOutputDir.mkdirs();

        try {
            @SuppressLint("PrivateApi")
            Class<?> d8Class = Class.forName("com.android.tools.r8.D8");

            return runD8ViaReflection(d8Class, classFiles, dexOutputDir);
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "D8 not available, using fallback: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "D8 reflection failed, using fallback: " + e.getMessage());
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

    @NonNull
    @Contract("_, _, _ -> new")
    @SuppressLint("PrivateApi")
    private DexResult runD8ViaReflection(@NonNull Class<?> d8Class, @NonNull List<File> classFiles, File outputDir)
            throws Exception {
        Method builderMethod = d8Class.getMethod("builder", File.class);
        Object builder = builderMethod.invoke(null, outputDir);

         Class<?> builderClass = Class.forName("com.android.tools.r8.D8$Command$Builder");
        
        // setMode
        Class<?> compilationModeClass = Class.forName("com.android.tools.r8.CompilationMode");
        Object debugMode = compilationModeClass.getField("DEBUG").get(null);
        builderClass.getMethod("setMode", compilationModeClass).invoke(builder, debugMode);

        // setOutputMode
        Class<?> outputModeClass = Class.forName("com.android.tools.r8.OutputMode");
        Object dexIndexed = outputModeClass.getField("DexIndexed").get(null);
        builderClass.getMethod("setOutputMode", outputModeClass).invoke(builder, dexIndexed);

        // setMinApiLevel
        builderClass.getMethod("setMinApiLevel", int.class).invoke(builder, 21);

        // setThreadCount
        builderClass.getMethod("setThreadCount", int.class).invoke(builder, 
            Runtime.getRuntime().availableProcessors());

        // addClasspath / addProgramFiles
        Method addClasspath = builderClass.getMethod("addClasspath", File.class);
        Method addProgramFiles = builderClass.getMethod("addProgramFiles", File.class);

        for (File classFile : classFiles) {
            if (classFile.isDirectory()) {
                addClasspath.invoke(builder, classFile);
            } else {
                addProgramFiles.invoke(builder, classFile);
            }
        }

        // build
        Object command = builderClass.getMethod("build").invoke(builder);

        // D8.run(command)
        Method runMethod = d8Class.getMethod("run", Class.forName("com.android.tools.r8.D8$Command"));
        runMethod.invoke(null, command);

        File classesDex = new File(outputDir, "classes.dex");
        if (classesDex.exists()) {
            return new DexResult(true, classesDex, null);
        } else {
            return new DexResult(false, null, "No classes.dex generated");
        }
    }

    private void createJarFromClassFiles(List<File> classFiles, File jarFile) throws IOException {
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
                new java.io.FileOutputStream(jarFile))) {
            
            for (File classFile : classFiles) {
                if (classFile.isDirectory()) {
                    addDirectoryToJar(jos, classFile, classFile, "");
                } else if (classFile.getName().endsWith(".class")) {
                    addFileToJar(jos, classFile, "");
                }
            }
        }
    }

    private void addDirectoryToJar(java.util.jar.JarOutputStream jos, File root, File dir, String prefix) 
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

    private void addFileToJar(java.util.jar.JarOutputStream jos, File file, String prefix) 
            throws IOException {
        String entryName = prefix + file.getName();
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            java.util.jar.JarEntry entry = new java.util.jar.JarEntry(entryName);
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