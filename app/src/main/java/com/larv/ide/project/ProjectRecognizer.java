package com.larv.ide.project;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProjectRecognizer {

    public static final String JAVA = "Java";
    public static final String PYTHON = "Python";
    public static final String JAVASCRIPT = "JavaScript";
    public static final String HTML = "HTML";
    public static final String CSS = "CSS";
    public static final String CPP = "C/C++";

    public static class Detection {
        public final List<String> languages = new ArrayList<>();
        public String primaryLanguage = null;
        public String entryFile = null;
    }

    public static String languageForExtension(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".java")) return JAVA;
        if (lower.endsWith(".py")) return PYTHON;
        if (lower.endsWith(".js")) return JAVASCRIPT;
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return HTML;
        if (lower.endsWith(".css")) return CSS;
        if (lower.endsWith(".c")) return CPP;
        if (lower.endsWith(".cpp") || lower.endsWith(".cc")
            || lower.endsWith(".h") || lower.endsWith(".hpp")) return CPP;
        return null;
    }

    public static boolean isRunnable(String name) {
        return languageForExtension(name) != null;
    }

    public static Detection detect(File root, String activeFilePath) {
        Detection detection = new Detection();
        Map<String, Integer> counts = new LinkedHashMap<>();

        if (activeFilePath != null && !activeFilePath.isEmpty()) {
            String lang = languageForExtension(activeFilePath);
            if (lang != null) {
                counts.put(lang, 1000);
                detection.primaryLanguage = lang;
            }
        }

        scan(root, counts, 0);
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!detection.languages.contains(e.getKey())) {
                detection.languages.add(e.getKey());
            }
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        if (detection.primaryLanguage == null && best != null) {
            detection.primaryLanguage = best;
        }
        detection.entryFile = findEntry(root);
        return detection;
    }

    private static void scan(File dir, Map<String, Integer> counts, int depth) {
        if (depth > 6) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        int visited = 0;
        for (File f : files) {
            if (f.getName().startsWith(".") || ++visited > 400) continue;
            if (f.isDirectory()) {
                scan(f, counts, depth + 1);
            } else {
                String lang = languageForExtension(f.getName());
                if (lang != null) {
                    counts.merge(lang, 1, Integer::sum);
                }
            }
        }
    }

    private static String findEntry(File root) {
        String[] candidates = {"Main.java", "main.py", "index.js", "index.html", "index.htm"};
        for (String c : candidates) {
            File f = new File(root, c);
            if (f.exists()) return f.getAbsolutePath();
        }
        return null;
    }
}
