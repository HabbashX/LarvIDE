package com.larv.ide.run;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class RunDispatcherRoutingTest {

    @Test
    public void javaCommandCompilesThenRuns() {
        List<String> cmd = RunDispatcher.buildTermuxCommand("Java", "Main.java", null);
        assertEquals(Arrays.asList("bash", "-c", "javac 'Main.java' && java 'Main'"), cmd);
    }

    @Test
    public void cppCommandCompilesThenExecutes() {
        List<String> cmd = RunDispatcher.buildTermuxCommand(
            "C/C++", "solver.cpp", null);
        assertEquals(3, cmd.size());
        assertEquals("clang++ -std=c++17 'solver.cpp' -o 'solver' && ./'solver'",
            cmd.get(2));
    }

    @Test
    public void explicitRunCommandWins() {
        List<String> explicit = Arrays.asList("cargo", "run");
        assertEquals(explicit,
            RunDispatcher.buildTermuxCommand("Rust", "main.rs", explicit));
    }

    @Test
    public void unknownLanguageWithoutCommandReturnsNull() {
        assertNull(RunDispatcher.buildTermuxCommand("Ruby", "app.rb", null));
    }

    @Test
    public void quotesEscapeSingleQuotesSafely() {
        List<String> cmd = RunDispatcher.buildTermuxCommand("Java", "my'class.java", null);
        assertEquals("javac 'my'\\''class.java' && java 'my'\\''class'",
            cmd.get(2));
    }
}
