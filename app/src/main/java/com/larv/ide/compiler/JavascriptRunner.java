package com.larv.ide.compiler;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.ImporterTopLevel;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class JavascriptRunner {

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

    public RunResult run(String source, String sourceName, List<File> preloadScripts,
                         OutputStream stdout, OutputStream stderr) {
        return run(source, sourceName, preloadScripts, stdout, stderr, null);
    }

    public RunResult run(String source, String sourceName, List<File> preloadScripts,
                         OutputStream stdout, OutputStream stderr, String[] programArgs) {
        long start = System.currentTimeMillis();
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);

            ImporterTopLevel scope = new ImporterTopLevel(cx);
            ScriptableObjectHelper.bindConsole(scope, stdout, stderr);
            if (programArgs != null && programArgs.length > 0) {
                Object[] converted = new Object[programArgs.length];
                for (int i = 0; i < programArgs.length; i++) {
                    converted[i] = programArgs[i];
                }
                scope.put("arguments", scope,
                    cx.newArray(scope, converted));
            }

            if (preloadScripts != null) {
                for (File script : preloadScripts) {
                    String code = readFile(script);
                    if (code == null) continue;
                    try {
                        cx.evaluateString(scope, code, script.getName(), 1, null);
                    } catch (RhinoException e) {
                        return new RunResult(false, "Dependency error in "
                            + script.getName() + ": " + e.getMessage(),
                            System.currentTimeMillis() - start);
                    }
                }
            }

            try {
                cx.evaluateString(scope, source,
                    sourceName == null ? "main.js" : sourceName, 1, null);
                return new RunResult(true, null, System.currentTimeMillis() - start);
            } catch (RhinoException e) {
                return new RunResult(false, e.getMessage()
                    + (e.lineNumber() > 0 ? " (line " + e.lineNumber() + ")" : ""),
                    System.currentTimeMillis() - start);
            } catch (Exception e) {
                return new RunResult(false, "Error: " + e.getMessage(),
                    System.currentTimeMillis() - start);
            }
        } finally {
            Context.exit();
        }
    }

    private String readFile(File file) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    private static class ScriptableObjectHelper {
        static void bindConsole(Scriptable scope, OutputStream stdout, OutputStream stderr) {
            org.mozilla.javascript.ScriptableObject global = (org.mozilla.javascript.ScriptableObject) scope;
            ConsoleSink log = new ConsoleSink(stdout);
            ConsoleSink err = new ConsoleSink(stderr != null ? stderr : stdout);
            global.put("print", global, new BaseFunctionPrinter(log));
            org.mozilla.javascript.ScriptableObject console = new org.mozilla.javascript.NativeObject();
            console.put("log", console, new BaseFunctionPrinter(log));
            console.put("info", console, new BaseFunctionPrinter(log));
            console.put("warn", console, new BaseFunctionPrinter(log));
            console.put("error", console, new BaseFunctionPrinter(err));
            global.put("console", global, console);
        }
    }

    private static class ConsoleSink {
        private final OutputStream out;
        ConsoleSink(OutputStream out) { this.out = out; }
        void write(String s) {
            try {
                out.write(s.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException ignored) {
            }
        }
    }

    private static class BaseFunctionPrinter extends org.mozilla.javascript.BaseFunction {
        private final ConsoleSink sink;
        BaseFunctionPrinter(ConsoleSink sink) { this.sink = sink; }
        @Override
        public Object call(org.mozilla.javascript.Context cx, Scriptable scope,
                           Scriptable thisObj, Object[] args) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(org.mozilla.javascript.ScriptRuntime.toString(args[i]));
            }
            sink.write(sb + "\n");
            return org.mozilla.javascript.Undefined.instance;
        }
    }
}
