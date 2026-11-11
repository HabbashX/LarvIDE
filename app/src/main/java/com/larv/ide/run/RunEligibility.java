package com.larv.ide.run;

import com.larv.ide.project.ProjectRecognizer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a file is actually runnable before anything touches the
 * Linux prefix. Two questions, no guessing:
 *
 * <ol>
 *   <li>Is the extension a runnable language?</li>
 *   <li>Does the file contain a real entry point? Java needs
 *       {@code static void main(String[])}, C/C++ needs {@code int main(}.</li>
 * </ol>
 *
 * Python/Node scripts have no {@code main} concept, so a non-empty file is
 * runnable. HTML/CSS are previewable rather than executed.
 *
 * Pure Java (no Android imports) so it is unit-testable on the JVM.
 */
public final class RunEligibility {

    /** Matches {@code static void main(String[] args)} in all common forms. */
    private static final Pattern JAVA_MAIN = Pattern.compile(
        "\\b(?:(?:public|static|final|synchronized|strictfp)\\s+)+void\\s+main\\s*"
            + "\\(\\s*(?:final\\s+)?String\\s*"
            + "(?:\\[\\s*\\]\\s*\\w+|\\.\\.\\.\\s*\\w+|\\w+\\s*\\[\\s*\\])\\s*\\)");

    private static final Pattern JAVA_PACKAGE = Pattern.compile(
        "(?m)^\\s*package\\s+([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)\\s*;");

    /** Declared class before the main method — {@code Main} in {@code Main.java}. */
    private static final Pattern JAVA_CLASS = Pattern.compile(
        "\\b(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+)*class\\s+([A-Za-z_]\\w*)");

    private static final Pattern C_MAIN = Pattern.compile(
        "\\b(?:int|void)\\s+main\\s*\\(");

    private static final Pattern COMMENTS = Pattern.compile(
        "//[^\\n]*|/\\*[\\s\\S]*?\\*/");

    public static final class Verdict {
        public final boolean runnable;
        public final String language;
        public final String reason;
        /** Simple class name for Java, null otherwise. */
        public final String className;
        /** Package name for Java ("" when in the default package). */
        public final String packageName;
        /** Fully-qualified main class for Java, null otherwise. */
        public final String mainClass;

        private Verdict(boolean runnable, String language, String reason,
                        String className, String packageName) {
            this.runnable = runnable;
            this.language = language;
            this.reason = reason;
            this.className = className;
            this.packageName = packageName == null ? "" : packageName;
            this.mainClass = className == null ? null
                : (this.packageName.isEmpty()
                    ? className : this.packageName + "." + className);
        }

        /** Compact line for the status bar, e.g. {@code Main.java · Java · main ✓}. */
        public String statusLabel(String fileName) {
            if (language == null) return fileName + " · not runnable";
            String tail;
            if (!runnable) {
                tail = reason;
            } else if (ProjectRecognizer.HTML.equals(language)
                || ProjectRecognizer.CSS.equals(language)) {
                tail = "preview";
            } else if (mainClass != null) {
                tail = "main ✓ · " + mainClass;
            } else {
                tail = "runnable ✓";
            }
            return fileName + " · " + language + " · " + tail;
        }
    }

    private RunEligibility() {
    }

    public static Verdict check(String fileName, String content) {
        if (fileName == null) {
            return fail("Not a runnable file");
        }
        String language = ProjectRecognizer.languageForExtension(fileName);
        if (language == null) {
            return new Verdict(false, null, "not runnable", null, null);
        }
        if (isBlank(content)) {
            return new Verdict(false, language, "file is empty", null, null);
        }

        if (ProjectRecognizer.JAVA.equals(language)) {
            // Cheap rejection before touching regex/comment stripping: most
            // keystrokes in a file without main should cost one indexOf().
            if (content.indexOf("main") < 0) {
                return new Verdict(false, language, "no main method", null, null);
            }
            String code = stripComments(content);
            Matcher main = JAVA_MAIN.matcher(code);
            if (!main.find() || !main.group().contains("static")) {
                return new Verdict(false, language, "no main method", null, null);
            }
            String pkg = packageOf(code);
            String cls = classNameBefore(code, main.start(), fileName);
            return new Verdict(true, language, null, cls, pkg);
        }
        if (ProjectRecognizer.CPP.equals(language)) {
            if (content.indexOf("main") < 0) {
                return new Verdict(false, language, "no main function", null, null);
            }
            if (!C_MAIN.matcher(stripComments(content)).find()) {
                return new Verdict(false, language, "no main function", null, null);
            }
            return new Verdict(true, language, null, null, null);
        }
        if (ProjectRecognizer.HTML.equals(language)
            || ProjectRecognizer.CSS.equals(language)) {
            return new Verdict(true, language, null, null, null);
        }
        // Python / JavaScript: a script is its own entry point.
        return new Verdict(true, language, null, null, null);
    }

    /** Convenience: true when the file can be executed or previewed. */
    public static boolean isRunnable(String fileName, String content) {
        return check(fileName, content).runnable;
    }

    private static Verdict fail(String reason) {
        return new Verdict(false, null, reason, null, null);
    }

    private static String packageOf(String code) {
        Matcher m = JAVA_PACKAGE.matcher(code);
        return m.find() ? m.group(1) : "";
    }

    /**
     * The class that owns the main method, not merely the file name: a
     * non-public entry class may legitimately differ from the file name.
     */
    private static String classNameBefore(String code, int mainIndex, String fileName) {
        Matcher m = JAVA_CLASS.matcher(code);
        String last = null;
        while (m.find()) {
            if (m.start() > mainIndex) break;
            last = m.group(1);
        }
        if (last != null) return last;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String stripComments(String code) {
        // Most files have no comments — avoid copying the whole source then.
        if (code.indexOf("//") < 0 && code.indexOf("/*") < 0) return code;
        return COMMENTS.matcher(code).replaceAll(" ");
    }

    /** Empty/whitespace-only check without allocating a trimmed copy. */
    private static boolean isBlank(String s) {
        if (s == null) return true;
        for (int i = 0, n = s.length(); i < n; i++) {
            if (s.charAt(i) > ' ') return false;
        }
        return true;
    }
}
