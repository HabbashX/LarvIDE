package com.larv.ide.runtime;

import android.content.Context;

import java.io.File;
import java.util.Locale;

public final class RuntimeRegistry {

    public enum Language {
        JAVA("java", "libjavalauncher.so", true),
        PYTHON("python", "libpyrun.so", false),
        JAVASCRIPT("javascript", null, true),
        CPP("cpp", "libclang.so", false),
        WEB("web", null, true);

        public final String id;
        public final String engineLibName;
        public final boolean builtin;

        Language(String id, String engineLibName, boolean builtin) {
            this.id = id;
            this.engineLibName = engineLibName;
            this.builtin = builtin;
        }

        public static Language fromExtension(String fileName) {
            String n = fileName.toLowerCase(Locale.ROOT);
            if (n.endsWith(".java")) return JAVA;
            if (n.endsWith(".py")) return PYTHON;
            if (n.endsWith(".js")) return JAVASCRIPT;
            if (n.endsWith(".c") || n.endsWith(".cpp") || n.endsWith(".cc")
                || n.endsWith(".h") || n.endsWith(".hpp")) return CPP;
            if (n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".css")) return WEB;
            return null;
        }
    }

    public enum Status {
        BUILTIN,
        INSTALLED,
        MISSING
    }

    private RuntimeRegistry() {
    }

    public static String engineLibName(Language language) {
        return language.engineLibName;
    }

    public static Status status(Context context, Language language) {
        return status(new File(context.getApplicationInfo().nativeLibraryDir), language);
    }

    public static Status status(File nativeLibraryDir, Language language) {
        if (language.builtin) return Status.BUILTIN;
        String lib = language.engineLibName;
        if (lib == null) return Status.MISSING;
        return new File(nativeLibraryDir, lib).exists() ? Status.INSTALLED : Status.MISSING;
    }
}
