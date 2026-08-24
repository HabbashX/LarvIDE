package com.larv.ide.compiler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.larv.ide.model.Diagnostic;
import com.larv.ide.model.OpenFile;

import java.io.File;
import java.util.List;

public class JavaCompilerSyntaxCheckTest {

    private final JavaCompiler compiler = createCompiler();

    private static JavaCompiler createCompiler() {
        File base = new File(new File(System.getProperty("java.io.tmpdir"), "larvjavac-test"), "x");
        return new JavaCompiler(new File(base, "cache"), new File(base, "out"));
    }

    @Test
    public void cleanFileHasNoSyntaxProblems() {
        OpenFile good = new OpenFile("/t/Main.java",
            "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        System.out.println(\"hi\");\n"
                + "    }\n"
                + "}\n");
        List<Diagnostic> diags = compiler.syntaxCheck(good);
        assertEquals("expected no diagnostics, got: " + diags, 0, diags.size());
    }

    @Test
    public void brokenSyntaxReportsErrors() {
        OpenFile bad = new OpenFile("/t/Broken.java",
            "public class Broken {\n"
                + "    void f() {\n"
                + "        int x = ;\n"
                + "    }\n"
                + "}\n");
        List<Diagnostic> diags = compiler.syntaxCheck(bad);
        assertTrue("expected syntax errors", !diags.isEmpty());
        boolean errorSeverity = false;
        for (Diagnostic d : diags) {
            if (d.getSeverity() == Diagnostic.Severity.ERROR) errorSeverity = true;
        }
        assertTrue(errorSeverity);
    }

    @Test
    public void missingQuoteIsCaught() {
        OpenFile missingQuotes = new OpenFile("/t/Main.java",
            "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        System.out.println(Hello from Larv IDE!);\n"
                + "    }\n"
                + "}\n");
        List<Diagnostic> diags = compiler.syntaxCheck(missingQuotes);
        assertTrue("unquoted string literal must be flagged", !diags.isEmpty());
    }
}
