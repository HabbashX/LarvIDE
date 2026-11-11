package com.larv.ide.run;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RunEligibilityTest {

    private static final String JAVA_MAIN = "public class Main {\n"
        + "    public static void main(String[] args) {\n"
        + "        System.out.println(\"hi\");\n"
        + "    }\n"
        + "}\n";

    @Test
    public void javaWithMainIsRunnableAndKnowsItsClass() {
        RunEligibility.Verdict v = RunEligibility.check("Main.java", JAVA_MAIN);
        assertTrue(v.runnable);
        assertEquals("Main", v.className);
        assertEquals("Main", v.mainClass);
        assertEquals("", v.packageName);
    }

    @Test
    public void javaWithoutMainIsNotRunnable() {
        RunEligibility.Verdict v = RunEligibility.check("Util.java",
            "public class Util { static int add(int a, int b) { return a + b; } }");
        assertFalse(v.runnable);
        assertEquals("no main method", v.reason);
    }

    @Test
    public void javaMainVariantsAreAccepted() {
        assertTrue(RunEligibility.check("A.java",
            "class A { static void main(String... args) {} }").runnable);
        assertTrue(RunEligibility.check("A.java",
            "class A { public static final void main(String args[]) {} }").runnable);
        assertTrue(RunEligibility.check("A.java",
            "class A { static public void main(final String[] argv) {} }").runnable);
        assertFalse(RunEligibility.check("A.java",
            "class A { void main(String[] args) {} }").runnable);
    }

    @Test
    public void commentedOutMainDoesNotCount() {
        RunEligibility.Verdict v = RunEligibility.check("Main.java",
            "class Main {\n"
                + "  // public static void main(String[] args) {}\n"
                + "  /* public static void main(String[] args) {} */\n"
                + "}");
        assertFalse(v.runnable);
    }

    @Test
    public void javaPackageProducesFullyQualifiedMainClass() {
        RunEligibility.Verdict v = RunEligibility.check("Main.java",
            "package com.foo.app;\n" + JAVA_MAIN);
        assertTrue(v.runnable);
        assertEquals("com.foo.app", v.packageName);
        assertEquals("com.foo.app.Main", v.mainClass);
    }

    @Test
    public void cppNeedsMain() {
        assertTrue(RunEligibility.check("main.cpp",
            "int main() { return 0; }").runnable);
        assertTrue(RunEligibility.check("main.c",
            "#include <stdio.h>\nvoid main(void) {}").runnable);
        RunEligibility.Verdict v = RunEligibility.check("util.cpp",
            "int add(int a, int b) { return a + b; }");
        assertFalse(v.runnable);
        assertEquals("no main function", v.reason);
    }

    @Test
    public void scriptsAreRunnableWithoutMain() {
        assertTrue(RunEligibility.check("main.py", "print('hi')\n").runnable);
        assertTrue(RunEligibility.check("index.js", "console.log('hi');\n").runnable);
    }

    @Test
    public void emptyFileIsNotRunnable() {
        assertFalse(RunEligibility.check("main.py", "   \n").runnable);
    }

    @Test
    public void nonRunnableTypesAreRejected() {
        RunEligibility.Verdict v = RunEligibility.check("notes.txt", "hello");
        assertFalse(v.runnable);
        assertNull(v.language);
        assertEquals("notes.txt · not runnable", v.statusLabel("notes.txt"));
    }

    @Test
    public void webFilesArePreviewable() {
        assertTrue(RunEligibility.check("index.html", "<html></html>").runnable);
        assertTrue(RunEligibility.check("style.css", "body { color: red; }").runnable);
    }
}
