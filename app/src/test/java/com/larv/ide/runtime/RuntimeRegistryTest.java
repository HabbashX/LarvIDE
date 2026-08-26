package com.larv.ide.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;

public class RuntimeRegistryTest {

    @Test
    public void mapsExtensionsToLanguages() {
        assertEquals(RuntimeRegistry.Language.JAVA,
            RuntimeRegistry.Language.fromExtension("Main.java"));
        assertEquals(RuntimeRegistry.Language.PYTHON,
            RuntimeRegistry.Language.fromExtension("main.py"));
        assertEquals(RuntimeRegistry.Language.JAVASCRIPT,
            RuntimeRegistry.Language.fromExtension("index.js"));
        assertEquals(RuntimeRegistry.Language.CPP,
            RuntimeRegistry.Language.fromExtension("solver.cpp"));
        assertEquals(RuntimeRegistry.Language.WEB,
            RuntimeRegistry.Language.fromExtension("index.html"));
        assertNull(RuntimeRegistry.Language.fromExtension("notes.txt"));
    }

    @Test
    public void builtinLanguagesNeedNoEngine() {
        assertTrue(RuntimeRegistry.Language.JAVASCRIPT.builtin);
        assertTrue(RuntimeRegistry.Language.WEB.builtin);
        assertTrue(RuntimeRegistry.Language.JAVA.builtin
            || RuntimeRegistry.Language.JAVA.engineLibName != null);
    }

    @Test
    public void engineLibNamesFollowJniConvention() {
        assertEquals("libpyrun.so", RuntimeRegistry.engineLibName(
            RuntimeRegistry.Language.PYTHON));
        assertEquals("libclang.so", RuntimeRegistry.engineLibName(
            RuntimeRegistry.Language.CPP));
    }

    @Test
    public void statusDetectsInstalledEngineByFilePresence() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("nativetest");
        assertEquals(RuntimeRegistry.Status.MISSING,
            RuntimeRegistry.status(dir.toFile(), RuntimeRegistry.Language.PYTHON));

        java.nio.file.Files.write(dir.resolve("libpyrun.so"), new byte[]{1});
        assertEquals(RuntimeRegistry.Status.INSTALLED,
            RuntimeRegistry.status(dir.toFile(), RuntimeRegistry.Language.PYTHON));
    }

    @Test
    public void builtinLanguagesReportBuiltinWithoutFiles() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("nativetest2");
        assertEquals(RuntimeRegistry.Status.BUILTIN,
            RuntimeRegistry.status(dir.toFile(), RuntimeRegistry.Language.JAVASCRIPT));
    }
}
