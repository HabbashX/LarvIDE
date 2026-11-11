package com.larv.ide.run;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class RunDispatcherRoutingTest {

    @Test
    public void javaCompilesWithJavacAndRunsOnJre() {
        List<String> cmd = RunDispatcher.buildJavaCommand(
            "/p", "/p/.larv-classes", "com.foo.Main", null);
        assertEquals(Arrays.asList("bash", "-c",
            "javac -encoding UTF-8 -d '/p/.larv-classes' "
                + "$(find '/p' -path '*/.*' -prune -o -name '*.java' -print) "
                + "&& java -cp '/p/.larv-classes' 'com.foo.Main'"), cmd);
    }

    @Test
    public void javaReleaseLevelIsAppliedToJavac() {
        List<String> cmd = RunDispatcher.buildJavaCommand(
            "/p", "/p/.larv-classes", "Main", "11");
        assertTrue(cmd.get(2).contains("--release '11'"));
    }

    @Test
    public void cppCompilesThenExecutes() {
        List<String> cmd = RunDispatcher.buildCppCommand("solver.cpp", null);
        assertEquals(3, cmd.size());
        assertEquals("clang++ -std=c++17 'solver.cpp' -o 'solver' && ./'solver'",
            cmd.get(2));
    }

    @Test
    public void cppProgramArgsAreAppended() {
        List<String> cmd = RunDispatcher.buildCppCommand(
            "solver.cpp", Arrays.asList("1", "two words"));
        assertTrue(cmd.get(2).endsWith("./'solver' '1' 'two words'"));
    }

    @Test
    public void pythonAndNodeUsePrefixInterpreters() {
        assertEquals(Arrays.asList("bash", "-c", "python3 'main.py'"),
            RunDispatcher.buildScriptCommand("python3", "main.py", null));
        assertEquals(Arrays.asList("bash", "-c", "node 'index.js'"),
            RunDispatcher.buildScriptCommand("node", "index.js", null));
    }

    @Test
    public void scriptArgsAreQuoted() {
        List<String> cmd = RunDispatcher.buildScriptCommand(
            "python3", "main.py", Arrays.asList("hello world"));
        assertEquals("python3 'main.py' 'hello world'", cmd.get(2));
    }

    @Test
    public void quotesEscapeSingleQuotesSafely() {
        List<String> cmd = RunDispatcher.buildJavaCommand(
            "/p", "/p/.larv-classes", "my'class", null);
        assertTrue(cmd.get(2).contains("'my'\\''class'"));
    }
}
