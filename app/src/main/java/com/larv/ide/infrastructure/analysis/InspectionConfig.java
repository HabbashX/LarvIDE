package com.larv.ide.infrastructure.analysis;

import android.content.SharedPreferences;

import com.larv.ide.infrastructure.model.Diagnostic;

/**
 * Immutable snapshot of inspection settings. Read once per inspection run so
 * the hot path never touches SharedPreferences or the filesystem.
 */
public final class InspectionConfig {

    public static final String PREFIX = "inspection.";
    public static final String PREF_LIVE = PREFIX + "live";
    public static final String PREF_MAX_LINE = PREFIX + "maxLineLength";
    public static final String PREF_MIN_SEVERITY = PREFIX + "minSeverity";

    public final boolean live;
    public final int maxLineLength;
    public final Diagnostic.Severity minSeverity;
    public final boolean unusedImports;
    public final boolean trailingWhitespace;
    public final boolean longLines;
    public final boolean todoComments;
    public final boolean debugStatements;
    public final boolean emptyCatch;
    public final boolean stringComparison;
    public final boolean tabs;
    public final boolean syntax;

    public InspectionConfig(boolean live, int maxLineLength, Diagnostic.Severity minSeverity,
                            boolean unusedImports, boolean trailingWhitespace, boolean longLines,
                            boolean todoComments, boolean debugStatements, boolean emptyCatch,
                            boolean stringComparison, boolean tabs, boolean syntax) {
        this.live = live;
        this.maxLineLength = maxLineLength;
        this.minSeverity = minSeverity;
        this.unusedImports = unusedImports;
        this.trailingWhitespace = trailingWhitespace;
        this.longLines = longLines;
        this.todoComments = todoComments;
        this.debugStatements = debugStatements;
        this.emptyCatch = emptyCatch;
        this.stringComparison = stringComparison;
        this.tabs = tabs;
        this.syntax = syntax;
    }

    /** All checks enabled at default thresholds — also handy for unit tests. */
    public static InspectionConfig allEnabled() {
        return new InspectionConfig(true, 120, Diagnostic.Severity.INFO,
            true, true, true, true, true, true, true, false, true);
    }

    public static InspectionConfig from(SharedPreferences prefs) {
        String severityName = prefs.getString(PREF_MIN_SEVERITY, "INFO");
        Diagnostic.Severity minSeverity;
        try {
            minSeverity = Diagnostic.Severity.valueOf(severityName);
        } catch (Exception e) {
            minSeverity = Diagnostic.Severity.INFO;
        }
        return new InspectionConfig(
            prefs.getBoolean(PREF_LIVE, true),
            prefs.getInt(PREF_MAX_LINE, 120),
            minSeverity,
            prefs.getBoolean(PREFIX + "unusedImports", true),
            prefs.getBoolean(PREFIX + "trailingWhitespace", true),
            prefs.getBoolean(PREFIX + "longLines", true),
            prefs.getBoolean(PREFIX + "todoComments", true),
            prefs.getBoolean(PREFIX + "debugStatements", true),
            prefs.getBoolean(PREFIX + "emptyCatch", true),
            prefs.getBoolean(PREFIX + "stringComparison", true),
            prefs.getBoolean(PREFIX + "tabs", false),
            prefs.getBoolean(PREFIX + "syntax", true)
        );
    }

    public boolean shows(Diagnostic.Severity severity) {
        return severity.ordinal() <= minSeverity.ordinal();
    }
}
