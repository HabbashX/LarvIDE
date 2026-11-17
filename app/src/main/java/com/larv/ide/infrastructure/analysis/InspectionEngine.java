package com.larv.ide.infrastructure.analysis;

import com.larv.ide.infrastructure.model.Diagnostic;
import com.larv.ide.infrastructure.performance.PerformanceConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fast, allocation-light source inspections for the current editor file.
 *
 * <p>Performance rules for 2 GB devices:
 * <ul>
 *   <li>one pass over lines for cheap checks, no regex compilation per line;</li>
 *   <li>no full-file tokenisation or AST — only bounded string scans;</li>
 *   <li>hard caps on findings, line length and files inspected;</li>
 *   <li>settings are passed in as an immutable snapshot.</li>
 * </ul>
 */
public final class InspectionEngine {

    private static final String[] TODO_MARKERS = {"TODO", "FIXME", "HACK", "XXX"};
    private static final int MAX_LINE_SCAN = 4096;

    private InspectionEngine() {}

    public static List<Diagnostic> inspect(String filePath, String content, InspectionConfig cfg) {
        if (content == null || content.isEmpty() || cfg == null || !cfg.live) {return Collections.emptyList();}
        String name = filePath == null ? "buffer" : new java.io.File(filePath).getName();
        String ext = extensionOf(name);
        boolean java = "java".equals(ext);
        boolean cpp = "c".equals(ext) || "cc".equals(ext) || "cpp".equals(ext)
            || "cxx".equals(ext) || "h".equals(ext) || "hpp".equals(ext);
        boolean python = "py".equals(ext);
        boolean js = "js".equals(ext) || "ts".equals(ext)
            || "jsx".equals(ext) || "tsx".equals(ext);

        List<Diagnostic> out = new ArrayList<>(24);
        int limit = PerformanceConfig.MAX_INSPECTION_FINDINGS;

        int lineStart = 0;
        int lineNo = 1;
        final int length = content.length();
        while (lineStart <= length && out.size() < limit) {
            int nl = content.indexOf('\n', lineStart);
            int end = nl < 0 ? length : nl;
            if (end > lineStart && content.charAt(end - 1) == '\r') end--;
            int len = end - lineStart;
            if (len > 0 && len <= MAX_LINE_SCAN) {
                scanLine(content, lineStart, end, lineNo, name, cfg, java, python, js, out, limit);
                if (cfg.syntax) {checkSyntaxLine(content, lineStart, end, lineNo, name, java, cpp, python, js, out, limit);}
            }
            if (nl < 0) break;
            lineStart = nl + 1;
            lineNo++;
        }

        if (cfg.syntax && out.size() < limit && (java || cpp || js)) {
            checkDelimiters(content, name, out, limit);
        }

        if (out.size() < limit && cfg.unusedImports && (java || python)) {
            scanImports(content, name, cfg, out, limit);
        }
        if (out.size() < limit && cfg.emptyCatch && java) {
            scanEmptyCatch(content, name, cfg, out, limit);
        }

        // Apply the severity threshold in one pass. Ordinals are ordered
        // ERROR(0), WARNING(1), WEAK_WARNING(2), INFO(3).
        List<Diagnostic> result = new ArrayList<>(out.size());
        for (Diagnostic d : out) {
            if (cfg.shows(d.getSeverity())) result.add(d);
        }
        return result;
    }

    private static void scanLine(String content, int start, int end, int line, String file,
                                 InspectionConfig cfg, boolean java, boolean python, boolean js,
                                 List<Diagnostic> out, int limit) {
        // Trailing whitespace.
        if (cfg.trailingWhitespace && end > start) {
            char last = content.charAt(end - 1);
            if (last == ' ' || last == '\t') {
                add(out, limit, file, line, end, "Trailing whitespace",
                    Diagnostic.Severity.WEAK_WARNING, "trailing-whitespace");
            }
        }
        // Over-long lines: only when enabled and under the cheap scan bound.
        if (cfg.longLines && end - start > cfg.maxLineLength) {
            add(out, limit, file, line, start + 1,
                "Line is " + (end - start) + " characters (limit " + cfg.maxLineLength + ")",
                Diagnostic.Severity.WEAK_WARNING, null);
        }
        // Tab characters in indentation.
        if (cfg.tabs) {
            for (int i = start; i < end; i++) {
                if (content.charAt(i) == '\t') {
                    add(out, limit, file, line, i + 1, "Tab character — prefer spaces",
                        Diagnostic.Severity.WEAK_WARNING, null);
                    break;
                }
            }
        }

        boolean comment = isCommentLine(content, start, end);

        // TODO / FIXME markers.
        if (cfg.todoComments && end - start <= 256) {
            for (String marker : TODO_MARKERS) {
                int at = indexOfIgnoreCase(content, start, end, marker);
                if (at >= 0) {
                    add(out, limit, file, line, at + 1, marker + " comment",
                        Diagnostic.Severity.INFO, null);
                    break;
                }
            }
        }

        if (comment) return;

        // Debug printing.
        if (cfg.debugStatements) {
            if ((java && (contains(content, start, end, "System.out.print")
                    || contains(content, start, end, "System.err.print")))
                || (python && contains(content, start, end, "print("))
                || (js && (contains(content, start, end, "console.log(")
                    || contains(content, start, end, "debugger")))) {
                add(out, limit, file, line, start + 1, "Debug output left in source",
                    Diagnostic.Severity.INFO, null);
            }
        }

        // String equality with == (Java/JS). Cheap textual check; no AST.
        if (cfg.stringComparison && (java || js)) {
            int eq = indexOf(content, start, end, " == ");
            if (eq >= 0 && (contains(content, eq + 4, end, "\"")
                    || contains(content, start, eq, "\""))) {
                add(out, limit, file, line, eq + 1, "Use equals()/=== instead of == for strings",
                    Diagnostic.Severity.WARNING, null);
            }
        }
    }

    /** Collect imports, flag duplicates, and report those never used below. */
    private static void scanImports(String content, String file, InspectionConfig cfg,
                                    List<Diagnostic> out, int limit) {
        List<int[]> imports = new ArrayList<>(16); // {lineStart, lineEnd, simpleNameStart}
        Set<String> seen = new HashSet<>();
        int lineStart = 0;
        int lineNo = 1;
        final int length = content.length();
        while (lineStart <= length && imports.size() < 64) {
            int nl = content.indexOf('\n', lineStart);
            int rawEnd = nl < 0 ? length : nl;
            int end = rawEnd;
            if (end > lineStart && content.charAt(end - 1) == '\r') end--;
            int s = firstNonSpace(content, lineStart, end);
            if (s >= 0 && startsWith(content, s, end, "import ")) {
                int nameStart = s + 7;
                int nameEnd = content.indexOf(';', nameStart);
                if (nameEnd < 0 || nameEnd > end) nameEnd = end;
                String full = content.substring(nameStart, nameEnd).trim();
                if (!full.isEmpty() && full.indexOf('*') < 0 && full.indexOf("static ") != 0) {
                    if (!seen.add(full)) {
                        add(out, limit, file, lineNo, s + 1, "Duplicate import " + full,
                            Diagnostic.Severity.WARNING, null);
                    }
                    // {searchFrom, fileLength, nameStart, lineNumber, nameEnd}
                    imports.add(new int[]{rawEnd + 1, length, nameStart, lineNo, nameEnd});
                }
            }
            if (nl < 0) break;
            lineStart = nl + 1;
            lineNo++;
        }
        for (int[] ref : imports) {
            if (out.size() >= limit) break;
            int searchFrom = ref[0];
            String simple = simpleNameOf(content, ref[2], ref[4]);
            if (simple == null || simple.isEmpty()) continue;
            boolean used = false;
            int pos = searchFrom;
            while (pos < ref[1]) {
                int hit = content.indexOf(simple, pos);
                if (hit < 0) break;
                int after = hit + simple.length();
                boolean leftOk = hit == 0 || !isIdentifierPart(content.charAt(hit - 1));
                boolean rightOk = after >= content.length() || !isIdentifierPart(content.charAt(after));
                if (leftOk && rightOk) { used = true; break; }
                pos = after;
            }
            if (!used) {
                add(out, limit, file, ref[3], ref[2] + 1,
                    "Unused import '" + simple + "'",
                    Diagnostic.Severity.WARNING, "unused-import");
            }
        }
    }

    private static String simpleNameOf(String content, int start, int end) {
        String full = content.substring(start, end).trim();
        int dot = full.lastIndexOf('.');
        return dot < 0 ? full : full.substring(dot + 1);
    }

    private static void scanEmptyCatch(String content, String file, InspectionConfig cfg,
                                       List<Diagnostic> out, int limit) {
        int pos = 0;
        while (pos < content.length() && out.size() < limit) {
            int at = content.indexOf("catch", pos);
            if (at < 0) break;
            int open = content.indexOf('(', at + 5);
            if (open < 0 || open - at > 24) { pos = at + 5; continue; }
            int close = content.indexOf(')', open + 1);
            if (close < 0 || close - open > 256) { pos = at + 5; continue; }
            int brace = nextNonWhitespace(content, close + 1);
            if (brace < 0 || content.charAt(brace) != '{') { pos = at + 5; continue; }
            int next = nextNonWhitespace(content, brace + 1);
            if (next > 0 && content.charAt(next) == '}') {
                add(out, limit, file, lineOf(content, at), at + 1,
                    "Empty catch block — handle or explain the exception",
                    Diagnostic.Severity.WARNING, null);
            }
            pos = at + 5;
        }
    }

    /** Cheap per-line syntax heuristics (no parser, no regex). */
    private static void checkSyntaxLine(String content, int start, int end, int line, String file,
                                        boolean java, boolean cpp, boolean python, boolean js,
                                        List<Diagnostic> out, int limit) {
        int s = firstNonSpace(content, start, end);
        if (s < 0) return;
        if ((end - s) >= 2 && content.charAt(s) == '/' && content.charAt(s + 1) == '/') return;

        if (python) {
            int wordEnd = s;
            while (wordEnd < end && isIdentifierPart(content.charAt(wordEnd))) wordEnd++;
            String word = content.substring(s, wordEnd);
            boolean blockKeyword = "if".equals(word) || "elif".equals(word) || "else".equals(word)
                || "for".equals(word) || "while".equals(word) || "def".equals(word)
                || "class".equals(word) || "try".equals(word) || "except".equals(word)
                || "finally".equals(word) || "with".equals(word);
            if (blockKeyword) {
                int last = lastNonSpace(content, s, end);
                if (last < 0 || content.charAt(last) != ':') {
                    add(out, limit, file, line, s + 1,
                        "Syntax: expected ':' after '" + word + "'",
                        Diagnostic.Severity.ERROR, null);
                }
            }
            return;
        }

        if (java || cpp || js) {
            int last = lastNonSpace(content, s, end);
            if (last < 0) return;
            char lastChar = content.charAt(last);
            // A method call or assignment line that does not end in a
            // statement terminator/block opener is almost certainly a typo.
            boolean callEnd = lastChar == ')';
            boolean stringEnd = lastChar == '"' || lastChar == '\'';
            if (!callEnd && !stringEnd) return;
            if (lastChar == ';' || lastChar == '{' || lastChar == '}') return;
            String lineText = content.substring(s, last + 1);
            if (startsKeyword(lineText, "if") || startsKeyword(lineText, "for")
                || startsKeyword(lineText, "while") || startsKeyword(lineText, "switch")
                || startsKeyword(lineText, "catch") || startsKeyword(lineText, "return")) {
                return;
            }
            if (lineText.indexOf('=') < 0 && lineText.indexOf('(') < 0) return;
            // Chained call: next non-empty line starts with '.'
            int next = nextNonWhitespace(content, end + 1);
            if (next > 0 && content.charAt(next) == '.') return;
            add(out, limit, file, line, last + 1, "Syntax: possible missing ';'",
                Diagnostic.Severity.WARNING, null);
        }
    }

    /**
     * Single character scan for unbalanced braces/parens/brackets, ignoring
     * strings and comments. Uses fixed arrays — no per-character allocations.
     */
    private static void checkDelimiters(String content, String file,
                                        List<Diagnostic> out, int limit) {
        final int maxDepth = 256;
        int[] lines = new int[maxDepth];
        int[] cols = new int[maxDepth];
        char[] opens = new char[maxDepth];
        int depth = 0;
        int line = 1;
        int col = 1;
        boolean lineComment = false;
        boolean blockComment = false;
        char quote = 0;
        int quoteLine = 0;
        int quoteCol = 0;

        for (int i = 0; i < content.length() && out.size() < limit; i++) {
            char c = content.charAt(i);
            if (c == '\n') {
                line++;
                col = 1;
                lineComment = false;
                if (quote != 0) {
                    add(out, limit, file, quoteLine, quoteCol,
                        "Syntax: unterminated string literal",
                        Diagnostic.Severity.ERROR, null);
                    quote = 0;
                }
                continue;
            }
            col++;
            if (lineComment) continue;
            if (blockComment) {
                if (c == '*' && i + 1 < content.length() && content.charAt(i + 1) == '/') {
                    i++;
                    col++;
                    blockComment = false;
                }
                continue;
            }
            if (c == '/' && i + 1 < content.length()) {
                char n = content.charAt(i + 1);
                if (n == '/') { lineComment = true; i++; col++; continue; }
                if (n == '*') { blockComment = true; i++; col++; continue; }
            }
            if (quote != 0) {
                if (c == '\\') { i++; col++; continue; }
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                quoteLine = line;
                quoteCol = col;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                if (depth < maxDepth) {
                    opens[depth] = c;
                    lines[depth] = line;
                    cols[depth] = col;
                    depth++;
                }
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                char expected = c == ')' ? '(' : c == ']' ? '[' : '{';
                if (depth > 0 && opens[depth - 1] == expected) {
                    depth--;
                } else {
                    add(out, limit, file, line, col,
                        "Syntax: unexpected '" + c + "'",
                        Diagnostic.Severity.ERROR, null);
                }
            }
        }
        if (depth > 0 && out.size() < limit) {
            char open = opens[0];
            char close = open == '(' ? ')' : open == '[' ? ']' : '}';
            add(out, limit, file, lines[0], cols[0],
                "Syntax: unclosed '" + open + "' — expected '" + close + "'",
                Diagnostic.Severity.ERROR, null);
        }
    }

    private static int lastNonSpace(String s, int start, int end) {
        for (int i = end - 1; i >= start; i--) {
            if (s.charAt(i) > ' ') return i;
        }
        return -1;
    }

    private static boolean startsKeyword(String line, String keyword) {
        return line.startsWith(keyword) && (line.length() == keyword.length()
            || !isIdentifierPart(line.charAt(keyword.length())));
    }

    private static void add(List<Diagnostic> out, int limit, String file, int line,
                            int column, String message, Diagnostic.Severity severity,
                            String code) {
        if (out.size() >= limit) return;
        Diagnostic d = new Diagnostic(file, line, Math.max(1, column), message, severity);
        d.setEndLine(line);
        d.setEndColumn(Math.max(2, column + 1));
        d.setCode(code);
        out.add(d);
    }

    private static boolean isCommentLine(String content, int start, int end) {
        int i = firstNonSpace(content, start, end);
        if (i < 0) return false;
        if (i + 1 < end && content.charAt(i) == '/' && content.charAt(i + 1) == '/') return true;
        return i < end && content.charAt(i) == '*';
    }

    private static int firstNonSpace(String s, int start, int end) {
        for (int i = start; i < end; i++) {
            if (s.charAt(i) > ' ') return i;
        }
        return -1;
    }

    private static int nextNonWhitespace(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > ' ') return i;
        }
        return -1;
    }

    private static boolean startsWith(String s, int start, int end, String needle) {
        if (end - start < needle.length()) return false;
        return s.regionMatches(start, needle, 0, needle.length());
    }

    private static boolean contains(String s, int start, int end, String needle) {
        if (start < 0) start = 0;
        if (end > s.length()) end = s.length();
        return indexOf(s, start, end, needle) >= 0;
    }

    private static int indexOf(String s, int start, int end, String needle) {
        if (start < 0) start = 0;
        if (end > s.length()) end = s.length();
        if (needle.isEmpty() || end - start < needle.length()) return -1;
        int max = end - needle.length();
        char first = needle.charAt(0);
        for (int i = start; i <= max; i++) {
            if (s.charAt(i) == first && s.regionMatches(i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    private static int indexOfIgnoreCase(String s, int start, int end, String needle) {
        if (end - start < needle.length()) return -1;
        int max = end - needle.length();
        for (int i = start; i <= max; i++) {
            if (s.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    private static boolean isIdentifierPart(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        int limit = Math.min(offset, content.length());
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
