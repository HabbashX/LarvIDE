package com.larv.ide.setup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java model for the first-launch setup wizard: which languages the user
 * works in and which prefix packages each one needs. No Android dependencies
 * so it is unit-testable on the JVM.
 */
public final class SetupConfig {

    public static final String PREF_SETUP_COMPLETE = "setup_complete";
    public static final String PREF_SETUP_LANGUAGES = "setup_languages";

    public enum Language {
        JAVA("Java", "openjdk-17",
            "Java coursework — compiles with javac, runs on ART"),
        CPP("C / C++", "clang",
            "Native code — compiles with clang++"),
        PYTHON("Python", "python",
            "Scripts and assignments — runs with python3"),
        NODE("JavaScript / Node", "nodejs",
            "JS beyond the browser — runs with node");

        public final String title;
        public final String pkg;
        public final String blurb;

        Language(String title, String pkg, String blurb) {
            this.title = title;
            this.pkg = pkg;
            this.blurb = blurb;
        }
    }

    private SetupConfig() {}

    /** Ordered language → package map for batch install. */
    public static Map<Language, String> packagesFor(List<Language> selected) {
        Map<Language, String> out = new LinkedHashMap<>();
        if (selected == null) return out;
        for (Language l : selected) {
            if (l != null && !out.containsKey(l)) out.put(l, l.pkg);
        }
        return out;
    }

    /** Parse persisted language names back; unknown names are dropped. */
    public static List<Language> parseStored(java.util.Set<String> stored) {
        List<Language> out = new ArrayList<>();
        if (stored == null) return out;
        for (String name : stored) {
            try {
                out.add(Language.valueOf(name));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    public static java.util.Set<String> storeNames(List<Language> selected) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (selected == null) return out;
        for (Language l : selected) {
            if (l != null) out.add(l.name());
        }
        return out;
    }
}
