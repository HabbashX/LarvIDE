package com.larv.ide.compiler;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.larv.ide.model.Diagnostic;
import com.larv.ide.model.OpenFile;

import org.eclipse.jdt.internal.compiler.batch.Main;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JavaCompiler {
    private static final String TAG = "JavaCompiler";
    private final Context context;
    private final File cacheDir;
    private final File outputDir;

    public JavaCompiler(@NonNull Context context) {
        this.context = context;
        this.cacheDir = new File(context.getCacheDir(), "javacache");
        this.outputDir = new File(context.getCacheDir(), "javaoutput");
        this.cacheDir.mkdirs();
        this.outputDir.mkdirs();
        extractBootClasspath();
    }

    private void extractBootClasspath() {
        try {
            File bootJar = new File(cacheDir, "android.jar");
            if (bootJar.exists() && bootJar.length() > 0) {
                Log.d(TAG, "Boot classpath already extracted: " + bootJar.length() + " bytes");
                return;
            }
            try (InputStream in = context.getAssets().open("bootclasspath/android.jar");
                 FileOutputStream out = new FileOutputStream(bootJar)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            Log.d(TAG, "Boot classpath extracted: " + bootJar.length() + " bytes at " + bootJar.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract boot classpath", e);
        }
    }

    public CompilationResult compile(@NonNull List<OpenFile> openFiles) {
        List<File> sourceFiles = new ArrayList<>();
        Map<String, String> fileContents = new java.util.HashMap<>();

        for (OpenFile openFile : openFiles) {
            File sourceFile = new File(cacheDir, openFile.getFileName());
            try (FileOutputStream fos = new FileOutputStream(sourceFile)) {
                fos.write(openFile.getContent().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.e(TAG, "Failed to write source file", e);
                return new CompilationResult(false, List.of(), List.of(
                    new Diagnostic(openFile.getFilePath(), 0, 0, 
                        "Failed to write source file: " + e.getMessage(), Diagnostic.Severity.ERROR)
                ), "Failed to write source file: " + e.getMessage());
            }
            sourceFiles.add(sourceFile);
            fileContents.put(openFile.getFileName(), openFile.getContent());
        }

        return compileFiles(sourceFiles, fileContents);
    }

    public CompilationResult compileFiles(List<File> sourceFiles, Map<String, String> fileContents) {
        List<String> args = new ArrayList<>();
        
        // Clean previous outputs so stale class files never linger
        clearDirectory(outputDir);
        
        // Output directory
        args.add("-d");
        args.add(outputDir.getAbsolutePath());
        
        // Source/target version (16 is the max supported by ECJ 3.27, the last
        // version that runs on a Java 8 runtime / Android < API 33)
        args.add("-source");
        args.add("16");
        args.add("-target");
        args.add("16");
        
        // Encoding
        args.add("-encoding");
        args.add("UTF-8");
        
        // Android's java.* implementation. ECJ rejects -bootclasspath at
        // compliance level 9+, so android.jar goes on the regular classpath.
        File bootJar = new File(cacheDir, "android.jar");
        if (bootJar.exists()) {
            args.add("-classpath");
            args.add(bootJar.getAbsolutePath());
        } else {
            Log.w(TAG, "bootclasspath/android.jar missing, ECJ will have no java.* types!");
        }
        
        // Continue on error to collect all errors
        args.add("-proceedOnError");
        
        // No warnings as errors
        args.add("-nowarn");
        
        // Disable annotation processing (its dispatch classes require
        // javax.lang.model which is not available on Android)
        args.add("-proc:none");
        
        // Source files
        for (File f : sourceFiles) {
            args.add(f.getAbsolutePath());
        }

        Log.d(TAG, "Compile args: " + String.join(" ", args));

        return runCompiler(args.toArray(new String[0]), fileContents);
    }

    private void clearDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                clearDirectory(f);
            }
            f.delete();
        }
    }

    public void clearOutputDirectory() {
        clearDirectory(outputDir);
    }

    public CompilationResult typeCheck(List<OpenFile> openFiles) {
        List<File> sourceFiles = new ArrayList<>();
        Map<String, String> fileContents = new java.util.HashMap<>();

        for (OpenFile openFile : openFiles) {
            File sourceFile = new File(cacheDir, openFile.getFileName());
            try (FileOutputStream fos = new FileOutputStream(sourceFile)) {
                fos.write(openFile.getContent().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.e(TAG, "Failed to write source file for type check", e);
            }
            sourceFiles.add(sourceFile);
            fileContents.put(openFile.getFileName(), openFile.getContent());
        }

        return compileFiles(sourceFiles, fileContents);
    }

    private CompilationResult runCompiler(String[] args, Map<String, String> fileContents) {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errStream = new ByteArrayOutputStream();
        
        PrintWriter out = new PrintWriter(outStream);
        PrintWriter err = new PrintWriter(errStream);

        try {
            Main compiler = new Main(out, err, false, null, null);
            boolean success = compiler.compile(args);
            
            out.flush();
            err.flush();
            
            String output = outStream.toString("UTF-8");
            String errors = errStream.toString("UTF-8");
            
            String rawOutput = "=== ECJ stdout ===\n" + output + "\n=== ECJ stderr ===\n" + errors;

            Log.d(TAG, "ECJ compile() returned success=" + success);
            logMultiline("ECJ stdout:\n" + output);
            if (!errors.isEmpty()) {
                Log.e(TAG, "ECJ stderr:\n" + errors);
            }
            
            List<Diagnostic> diagnostics = parseDiagnostics(errors, fileContents);
            if (!diagnostics.isEmpty()) {
                for (Diagnostic d : diagnostics) {
                    Log.e(TAG, "Diagnostic: " + d.getFilePath() + ":" + d.getLine() + " " + d.getMessage());
                }
            } else if (!success) {
                Log.e(TAG, "Compilation failed but no diagnostics were parsed. Raw output:\n" + rawOutput);
            }
            
            if (success && diagnostics.isEmpty()) {
                List<File> classFiles = collectClassFiles(outputDir);
                Log.d(TAG, "Compilation OK, class files: " + classFiles.size());
                return new CompilationResult(true, classFiles, diagnostics, rawOutput);
            } else {
                return new CompilationResult(false, List.of(), diagnostics, rawOutput);
            }
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e(TAG, "Compiler exception: " + msg, e);
            List<Diagnostic> diagnostics = List.of(
                new Diagnostic("", 0, 0, "Compiler error: " + msg, Diagnostic.Severity.ERROR)
            );
            return new CompilationResult(false, List.of(), diagnostics, "Compiler exception: " + msg);
        }
    }

    private void logMultiline(String text) {
        if (text == null || text.isEmpty()) return;
        for (String line : text.split("\n")) {
            Log.d(TAG, line);
        }
    }

    @NonNull
    private List<Diagnostic> parseDiagnostics(@NonNull String compilerOutput, Map<String, String> fileContents) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        
        String[] lines = compilerOutput.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            Diagnostic diagnostic = parseDiagnosticLine(line);
            if (diagnostic != null) {
                diagnostics.add(diagnostic);
            }
        }
        
        if (!diagnostics.isEmpty()) return diagnostics;

        return parseEcjProblems(compilerOutput);
    }

    private List<Diagnostic> parseEcjProblems(String output) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (output == null || output.isEmpty()) return diagnostics;

        java.util.regex.Pattern header = java.util.regex.Pattern.compile(
            "^\\s*\\d+\\.\\s+(ERROR|WARNING|INFO)\\s+in\\s+(.+?)\\s+\\(at line (\\d+)\\)\\s*$");

        Diagnostic current = null;
        StringBuilder message = new StringBuilder();

        for (String line : output.split("\n")) {
            java.util.regex.Matcher m = header.matcher(line);
            if (m.matches()) {
                if (current != null) {
                    current.setMessage(message.toString().trim());
                    diagnostics.add(current);
                }
                current = new Diagnostic();
                current.setFilePath(m.group(2).trim());
                current.setLine(Integer.parseInt(m.group(3)));
                current.setColumn(0);
                current.setSeverity(m.group(1).equals("ERROR")
                    ? Diagnostic.Severity.ERROR : Diagnostic.Severity.WARNING);
                message.setLength(0);
            } else if (current != null) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    if (message.length() > 0) message.append("\n");
                    message.append(t);
                }
            }
        }
        if (current != null) {
            current.setMessage(message.toString().trim());
            diagnostics.add(current);
        }
        return diagnostics;
    }

    private Diagnostic parseDiagnosticLine(String line) {

        int firstColon = line.indexOf(':');
        if (firstColon == -1) return null;
        
        String filePath = line.substring(0, firstColon).trim();
        String remainder = line.substring(firstColon + 1).trim();
        
        int secondColon = remainder.indexOf(':');
        if (secondColon == -1) return null;
        
        String lineStr = remainder.substring(0, secondColon).trim();
        remainder = remainder.substring(secondColon + 1).trim();
        
        int lineNum;
        try {
            lineNum = Integer.parseInt(lineStr);
        } catch (NumberFormatException e) {
            return null;
        }
        
        // Determine severity
        Diagnostic.Severity severity = Diagnostic.Severity.ERROR;
        if (remainder.startsWith("warning:")) {
            severity = Diagnostic.Severity.WARNING;
            remainder = remainder.substring(8).trim();
        } else if (remainder.startsWith("error:")) {
            severity = Diagnostic.Severity.ERROR;
            remainder = remainder.substring(6).trim();
        } else if (remainder.startsWith("info:")) {
            severity = Diagnostic.Severity.INFO;
            remainder = remainder.substring(5).trim();
        }
        
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setFilePath(filePath);
        diagnostic.setLine(lineNum);
        diagnostic.setColumn(0);
        diagnostic.setMessage(remainder);
        diagnostic.setSeverity(severity);
        
        return diagnostic;
    }

    private List<File> collectClassFiles(File dir) {
        List<File> classFiles = new ArrayList<>();
        collectClassFilesRecursive(dir, classFiles);
        return classFiles;
    }

    private void collectClassFilesRecursive(File dir, List<File> classFiles) {
        File[] files = dir.listFiles();
        if (files == null) return;
        
        for (File f : files) {
            if (f.isDirectory()) {
                collectClassFilesRecursive(f, classFiles);
            } else if (f.getName().endsWith(".class")) {
                classFiles.add(f);
            }
        }
    }

    public File getOutputDir() {
        return outputDir;
    }

    public static class CompilationResult {
        private final boolean success;
        private final List<File> classFiles;
        private final List<Diagnostic> diagnostics;
        private final String rawOutput;

        public CompilationResult(boolean success, List<File> classFiles, List<Diagnostic> diagnostics, String rawOutput) {
            this.success = success;
            this.classFiles = classFiles;
            this.diagnostics = diagnostics;
            this.rawOutput = rawOutput;
        }

        public boolean isSuccess() {
            return success;
        }

        public List<File> getClassFiles() {
            return classFiles;
        }

        public List<Diagnostic> getDiagnostics() {
            return diagnostics;
        }

        public String getRawOutput() {
            return rawOutput;
        }
    }
}