package com.larv.ide.run.backend.termux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.larv.ide.run.backend.ExecRequest;

import java.util.Arrays;
import java.util.List;

public class TermuxCommandBackendTest {

    private static final String BIN = TermuxCommandBackend.TERMUX_BIN;

    @Test
    public void simpleBinaryWrapsInBash() {
        ExecRequest req = new ExecRequest(
            Arrays.asList("python", "main.py", "--verbose"), "/w", true);
        TermuxCommandBackend.IntentSpec spec = TermuxCommandBackend.buildIntentSpec(req);

        assertEquals(BIN + "/bash", spec.path);
        assertEquals(2, spec.arguments.size());
        assertEquals("-c", spec.arguments.get(0));
        assertEquals("python main.py --verbose", spec.arguments.get(1));
        assertEquals("/w", spec.workdir);
        assertFalse(spec.background);
    }

    @Test
    public void explicitBashPassesArgumentsDirectly() {
        ExecRequest req = new ExecRequest(
            Arrays.asList("bash", "-lc", "javac Main.java && java Main"), "/proj", true);
        TermuxCommandBackend.IntentSpec spec = TermuxCommandBackend.buildIntentSpec(req);

        assertEquals(BIN + "/bash", spec.path);
        assertEquals(Arrays.asList("-lc", "javac Main.java && java Main"), spec.arguments);
    }

    @Test
    public void absolutePathIsUsedVerbatim() {
        ExecRequest req = new ExecRequest(
            Arrays.asList("/data/data/com.termux/files/usr/bin/clang", "a.c"), "/c", false);
        TermuxCommandBackend.IntentSpec spec = TermuxCommandBackend.buildIntentSpec(req);

        assertEquals("/data/data/com.termux/files/usr/bin/clang", spec.path);
        assertEquals(1, spec.arguments.size());
        assertEquals("a.c", spec.arguments.get(0));
        assertTrue(spec.background);
    }

    @Test
    public void interactiveFlagDrivesBackgroundExtra() {
        ExecRequest interactive = new ExecRequest(
            Arrays.asList("python"), "/", true);
        ExecRequest headless = new ExecRequest(
            Arrays.asList("python"), "/", false);

        assertTrue(!TermuxCommandBackend.buildIntentSpec(interactive).background);
        assertTrue(TermuxCommandBackend.buildIntentSpec(headless).background);
    }
}
