package com.larv.ide.compiler;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class PythonRunnerTest {

    @Test
    public void withoutEmbeddedRuntimeReportsGuidanceNotCrash() {
        PythonRunner runner = new PythonRunner(null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PythonRunner.RunResult r = runner.run("print('hi')", null, out, out);

        assertFalse(r.success);
        assertNotNull(r.error);
        String output = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(output.contains("Python runtime"));
    }
}
