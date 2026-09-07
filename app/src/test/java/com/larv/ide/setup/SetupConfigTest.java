package com.larv.ide.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SetupConfigTest {

    @Test
    public void eachLanguageMapsToOnePackage() {
        Map<SetupConfig.Language, String> map = SetupConfig.packagesFor(
            Arrays.asList(SetupConfig.Language.values()));
        assertEquals(4, map.size());
        assertEquals("openjdk-17", map.get(SetupConfig.Language.JAVA));
        assertEquals("clang", map.get(SetupConfig.Language.CPP));
        assertEquals("python", map.get(SetupConfig.Language.PYTHON));
        assertEquals("nodejs", map.get(SetupConfig.Language.NODE));
    }

    @Test
    public void duplicatesCollapse() {
        Map<SetupConfig.Language, String> map = SetupConfig.packagesFor(
            Arrays.asList(SetupConfig.Language.JAVA, SetupConfig.Language.JAVA));
        assertEquals(1, map.size());
    }

    @Test
    public void emptySelectionInstallsNothing() {
        assertTrue(SetupConfig.packagesFor(Collections.emptyList()).isEmpty());
        assertTrue(SetupConfig.packagesFor(null).isEmpty());
    }

    @Test
    public void storedNamesRoundTrip() {
        List<SetupConfig.Language> langs = Arrays.asList(
            SetupConfig.Language.JAVA, SetupConfig.Language.PYTHON);
        java.util.Set<String> stored = SetupConfig.storeNames(langs);
        List<SetupConfig.Language> back = SetupConfig.parseStored(stored);
        assertEquals(langs, back);
    }

    @Test
    public void unknownStoredNamesAreDropped() {
        java.util.Set<String> stored = new java.util.LinkedHashSet<>(
            Arrays.asList("JAVA", "COBOL"));
        List<SetupConfig.Language> back = SetupConfig.parseStored(stored);
        assertEquals(Collections.singletonList(SetupConfig.Language.JAVA), back);
    }
}
