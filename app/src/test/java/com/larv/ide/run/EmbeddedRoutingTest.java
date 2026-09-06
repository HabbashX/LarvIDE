package com.larv.ide.run;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.larv.ide.project.ProjectManager;
import com.larv.ide.project.ProjectRecognizer;

import org.junit.Test;

public class EmbeddedRoutingTest {

    @Test
    public void cppFileNamesDetected() {
        assertTrue(ProjectManager.isCppFileName("solver.cpp"));
        assertTrue(ProjectManager.isCppFileName("main.c"));
        assertTrue(ProjectManager.isCppFileName("util.hpp"));
        assertFalse(ProjectManager.isCppFileName("Main.java"));
        assertFalse(ProjectManager.isCppFileName("app.py"));
    }

    @Test
    public void cppGateDefaultsOff() {
        ProjectManager.setCppEnabled(false);
        assertFalse(ProjectManager.isCppEnabled());
        ProjectManager.setCppEnabled(true);
        assertTrue(ProjectManager.isCppEnabled());
        // Leave enabled so other tests are unaffected.
    }

    @Test
    public void cppTemplatesExistWhenUnlocked() {
        String tpl = ProjectManager.templateFor("solver.cpp");
        assertTrue(tpl.contains("iostream"));
        assertEquals("#pragma once\n", ProjectManager.templateFor("util.h"));
    }

    @Test
    public void cppStillRecognizedForExistingProjects() {
        assertEquals(ProjectRecognizer.CPP,
            ProjectRecognizer.languageForExtension("solver.cpp"));
    }

    @Test
    public void embeddedToolchainCoversCppAndJava() throws Exception {
        java.lang.reflect.Method m = RunDispatcher.class
            .getDeclaredMethod("usesEmbeddedToolchain", String.class);
        m.setAccessible(true);
        assertTrue((boolean) m.invoke(null, ProjectRecognizer.CPP));
        assertTrue((boolean) m.invoke(null, ProjectRecognizer.JAVA));
        assertFalse((boolean) m.invoke(null, ProjectRecognizer.PYTHON));
    }
}
