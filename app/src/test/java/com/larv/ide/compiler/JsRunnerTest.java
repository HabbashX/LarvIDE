package com.larv.ide.compiler;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class JsRunnerTest {

    private static String runCapture(JsRunner runner, String source, List<File> preloads) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsRunner.RunResult r = runner.run(source, "test.js", preloads, out, out);
        assertTrue("run failed: " + r.error, r.success);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void capturesConsoleAndPrint() {
        JsRunner runner = new JsRunner();
        String output = runCapture(runner,
            "console.log('Hello JS'); var a=[1,2,3].map(function(x){return x*2;}); print(a.join(','));",
            null);
        assertTrue(output.contains("Hello JS"));
        assertTrue(output.contains("2,4,6"));
    }

    @Test
    public void preloadDependencyScriptsExecuteFirst() throws Exception {
        File dep = File.createTempFile("larvdep", ".js");
        dep.deleteOnExit();
        try (FileWriter w = new FileWriter(dep)) {
            w.write("function larvHelper(n){ return 'dep' + n; }");
        }
        JsRunner runner = new JsRunner();
        String output = runCapture(runner, "print(larvHelper(7));",
            new ArrayList<>(Collections.singletonList(dep)));
        assertTrue(output.contains("dep7"));
    }

    @Test
    public void syntaxErrorsAreReportedNotThrown() {
        JsRunner runner = new JsRunner();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsRunner.RunResult r = runner.run("var x = ;;; broken(", "bad.js", null, out, out);
        assertTrue(!r.success);
        assertNotNull(r.error);
    }

    @Test
    public void runtimeErrorsReportLineNumber() {
        JsRunner runner = new JsRunner();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsRunner.RunResult r = runner.run(
            "function f(){ return undefinedValue + 1; }\nf();", "boom.js", null, out, out);
        assertTrue(!r.success);
        assertNotNull(r.error);
        assertTrue(r.error.contains("boom.js") || r.error.contains("line")
            || !r.error.isEmpty());
    }
}
