package com.larv.ide.infrastructure.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.larv.ide.infrastructure.model.Diagnostic;

import org.junit.Test;

import java.util.List;

public class InspectionEngineTest {

    @Test
    public void reportsAllSeverityLevels() {
        String source = "package p;\n"
            + "import java.util.List;   \n"
            + "public class T {\n"
            + "    // TODO finish this\n"
            + "    void go() {\n"
            + "        System.out.println(\"debug\");\n"
            + "        String veryLongVariableName = \"this line is deliberately made much longer than one hundred and twenty characters so the long line inspection reports it clearly\";\n"
            + "    }\n"
            + "}\n";
        List<Diagnostic> found = InspectionEngine.inspect("/p/T.java", source,
            InspectionConfig.allEnabled());

        assertTrue(has(found, Diagnostic.Severity.WARNING, "Unused import"));
        assertTrue(has(found, Diagnostic.Severity.WEAK_WARNING, "Trailing whitespace"));
        assertTrue(has(found, Diagnostic.Severity.WEAK_WARNING, "Line is"));
        assertTrue(has(found, Diagnostic.Severity.INFO, "TODO"));
        assertTrue(has(found, Diagnostic.Severity.INFO, "Debug output"));
    }

    @Test
    public void unusedImportQuickFixCodeIsSet() {
        String source = "package p;\nimport java.util.List;\npublic class T {}\n";
        List<Diagnostic> found = InspectionEngine.inspect("/p/T.java", source,
            InspectionConfig.allEnabled());
        Diagnostic unused = find(found, "Unused import");
        assertTrue(unused != null);
        assertEquals("unused-import", unused.getCode());
    }

    @Test
    public void emptyCatchIsAWarning() {
        String source = "class T { void go() { try { work(); } catch (Exception e) {} } }";
        List<Diagnostic> found = InspectionEngine.inspect("/T.java", source,
            InspectionConfig.allEnabled());
        assertTrue(has(found, Diagnostic.Severity.WARNING, "Empty catch block"));
    }

    @Test
    public void disabledChecksProduceNothing() {
        InspectionConfig cfg = new InspectionConfig(true, 120,
            Diagnostic.Severity.INFO, false, false, false, false, false, false, false, false);
        String source = "import java.util.List; // TODO\n";
        assertTrue(InspectionEngine.inspect("/T.java", source, cfg).isEmpty());
    }

    private static boolean has(List<Diagnostic> list, Diagnostic.Severity severity, String text) {
        return find(list, text) != null && find(list, text).getSeverity() == severity;
    }

    private static Diagnostic find(List<Diagnostic> list, String text) {
        for (Diagnostic d : list) {
            if (d.getMessage().contains(text)) return d;
        }
        return null;
    }
}
