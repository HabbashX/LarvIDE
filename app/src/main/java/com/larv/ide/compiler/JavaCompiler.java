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
                ));
            }
            sourceFiles.add(sourceFile);
            fileContents.put(openFile.getFileName(), openFile.getContent());
        }

        return compileFiles(sourceFiles, fileContents);
    }

    public CompilationResult compileFiles(List<File> sourceFiles, Map<String, String> fileContents) {
        List<String> args = new ArrayList<>();
        
        // Output directory
        args.add("-d");
        args.add(outputDir.getAbsolutePath());
        
        // Source/target version
        args.add("-source");
        args.add("21");
        args.add("-target");
        args.add("21");
        
        // Encoding
        args.add("-encoding");
        args.add("UTF-8");
        
        // Continue on error to collect all errors
        args.add("-proceedOnError");
        
        // No warnings as errors
        args.add("-nowarn");
        
        // Source files
        for (File f : sourceFiles) {
            args.add(f.getAbsolutePath());
        }

        return runCompiler(args.toArray(new String[0]), fileContents);
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
            
            List<Diagnostic> diagnostics = parseDiagnostics(errors, fileContents);
            
            if (success && diagnostics.isEmpty()) {
                List<File> classFiles = collectClassFiles(outputDir);
                return new CompilationResult(true, classFiles, diagnostics);
            } else {
                return new CompilationResult(false, List.of(), diagnostics);
            }
        } catch (Exception e) {
            Log.e(TAG, "Compiler exception", e);
            List<Diagnostic> diagnostics = List.of(
                new Diagnostic("", 0, 0, "Compiler error: " + e.getMessage(), Diagnostic.Severity.ERROR)
            );
            return new CompilationResult(false, List.of(), diagnostics);
        }
    }

    private List<Diagnostic> parseDiagnostics(String compilerOutput, Map<String, String> fileContents) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        
        // ECJ output format: filename.java:line: error: message
        String[] lines = compilerOutput.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            Diagnostic diagnostic = parseDiagnosticLine(line);
            if (diagnostic != null) {
                diagnostics.add(diagnostic);
            }
        }
        
        return diagnostics;
    }

    private Diagnostic parseDiagnosticLine(String line) {
        // Format: /path/to/File.java:10: error: message
        // or: File.java:10: warning: message
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

        public CompilationResult(boolean success, List<File> classFiles, List<Diagnostic> diagnostics) {
            this.success = success;
            this.classFiles = classFiles;
            this.diagnostics = diagnostics;
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
    }
}