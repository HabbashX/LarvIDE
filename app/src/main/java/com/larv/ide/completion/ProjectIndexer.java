package com.larv.ide.completion;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.larv.ide.model.OpenFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiresApi(api = Build.VERSION_CODES.N)

public class ProjectIndexer {
    private static final Pattern CLASS_PATTERN = Pattern.compile(
        "(?:public\\s+|private\\s+|protected\\s+|static\\s+|final\\s+|abstract\\s+)*" +
        "(?:class|interface|enum|record|@interface)\\s+(\\w+)"
    );

    private static final Pattern METHOD_PATTERN = Pattern.compile(
        "(?:public\\s+|private\\s+|protected\\s+|static\\s+|final\\s+|abstract\\s+|synchronized\\s+|native\\s+|strictfp\\s+)*" +
        "(?:<[^>]+>\\s+)?(\\w+(?:<[^>]+>)?)\\s+(\\w+)\\s*\\([^)]*\\)"
    );

    private static final Pattern FIELD_PATTERN = Pattern.compile(
        "(?:public\\s+|private\\s+|protected\\s+|static\\s+|final\\s+|transient\\s+|volatile\\s+)*" +
        "(?:\\w+(?:<[^>]+>)?)\\s+(\\w+)\\s*[=;]"
    );

    private static final Pattern IMPORT_PATTERN = Pattern.compile("import\\s+([\\w.]+);");
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("package\\s+([\\w.]+);");

    private static final String[][] KEYWORDS = {
        {"class", "Class declaration", "class ${1:Name} {\n    ${2}\n}"},
        {"interface", "Interface declaration", "interface ${1:Name} {\n    ${2}\n}"},
        {"enum", "Enum declaration", "enum ${1:Name} {\n    ${2}\n}"},
        {"record", "Record declaration", "record ${1:Name}(${2}) {}"},
        {"public", "Public modifier", "public "},
        {"private", "Private modifier", "private "},
        {"protected", "Protected modifier", "protected "},
        {"static", "Static modifier", "static "},
        {"final", "Final modifier", "final "},
        {"abstract", "Abstract modifier", "abstract "},
        {"if", "If statement", "if (${1:condition}) {\n    ${2}\n}"},
        {"else", "Else block", "else {\n    ${1}\n}"},
        {"for", "For loop", "for (${1:int i = 0; i < n; i++}) {\n    ${2}\n}"},
        {"foreach", "For-each loop", "for (${1:Type} ${2:item} : ${3:collection}) {\n    ${4}\n}"},
        {"while", "While loop", "while (${1:condition}) {\n    ${2}\n}"},
        {"try", "Try-catch", "try {\n    ${1}\n} catch (${2:Exception} e) {\n    ${3}\n}"},
        {"switch", "Switch expression", "switch (${1:expr}) {\n    case ${2} -> ${3};\n    default -> ${4};\n}"},
        {"return", "Return statement", "return ${1;};"},
        {"new", "New instance", "new ${1:ClassName}(${2})"},
        {"var", "Local variable type inference", "var ${1:name} = ${2:value};"},
        {"System.out.println", "Print line", "System.out.println(${1});"},
        {"main", "Main method", "public static void main(String[] args) {\n    ${1}\n}"}
    };

    private final Map<String, FileSymbols> fileIndex = new ConcurrentHashMap<>();
    private final Map<String, List<CompletionItem>> stdlibIndex = new ConcurrentHashMap<>();
    private final Map<String, String> classToPackage = new ConcurrentHashMap<>();

    /** Built once — sorting used to rebuild this comparator on every keystroke. */
    private static final Comparator<CompletionItem> COMPLETION_ORDER =
        Comparator.comparingInt(CompletionItem::getSortPriority)
            .thenComparingInt(CompletionItem::getKindOrder);

    public ProjectIndexer() {
        buildStdlibIndex();
    }

    public String findImportCandidates(String className) {
        if (className == null || className.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        String exact = classToPackage.get(className);
        boolean first = true;
        if (exact != null) {
            sb.append('"').append(exact).append('.').append(className).append('"');
            first = false;
        }
        for (Map.Entry<String, String> e : classToPackage.entrySet()) {
            if (e.getKey().equalsIgnoreCase(className) && !e.getKey().equals(className)) {
                if (!first) sb.append(',');
                sb.append('"').append(e.getValue()).append('.').append(e.getKey()).append('"');
                first = false;
            }
            if (sb.length() > 220) break;
        }
        for (Map.Entry<String, FileSymbols> entry : fileIndex.entrySet()) {
            FileSymbols symbols = entry.getValue();
            if (symbols.packageName == null || symbols.packageName.isEmpty()) continue;
            for (ClassSymbol cls : symbols.classes) {
                if (cls.name.equals(className)) {
                    if (!first) sb.append(',');
                    sb.append('"').append(symbols.packageName).append('.').append(cls.name).append('"');
                    first = false;
                    break;
                }
            }
        }
        sb.append(']');
        return sb.toString();
    }

    public void indexFile(OpenFile openFile) {
        String content = openFile.getContent();
        String fileName = openFile.getFileName();

        FileSymbols symbols = new FileSymbols();
        symbols.fileName = fileName;
        symbols.packageName = extractPackage(content);
        symbols.imports = extractImports(content);
        symbols.classes = extractClasses(content);
        symbols.methods = extractMethods(content);
        symbols.fields = extractFields(content);

        fileIndex.put(fileName, symbols);
    }

    public void removeFile(String fileName) {
        fileIndex.remove(fileName);
    }

    public void clear() {
        fileIndex.clear();
    }

    private FileSymbols resolveCurrentSymbols(String currentFile) {
        if (currentFile == null) return null;
        FileSymbols symbols = fileIndex.get(currentFile);
        if (symbols != null) return symbols;
        String name = new java.io.File(currentFile).getName();
        return fileIndex.get(name);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public List<CompletionItem> getCompletions(@NonNull String prefix, String currentFile, int line, int column) {
        List<CompletionItem> completions = new ArrayList<>(48);
        Set<String> seen = new HashSet<>();

        FileSymbols currentSymbols = resolveCurrentSymbols(currentFile);
        Set<String> currentImports = currentSymbols != null
            ? currentSymbols.imports : java.util.Collections.emptySet();

        if (currentSymbols != null) {
            addCompletionsFromSymbols(completions, seen, currentSymbols, prefix, true);
        }

        for (Map.Entry<String, FileSymbols> entry : fileIndex.entrySet()) {
            if (!entry.getKey().equals(currentFile)) {
                addCompletionsFromSymbols(completions, seen, entry.getValue(), prefix,
                    isAccessible(entry.getValue(), currentImports));
            }
        }

        addStdlibCompletions(completions, seen, currentImports, prefix);

        addKeywordsAndSnippets(completions, prefix);

        completions.sort(COMPLETION_ORDER);

        return completions;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public List<CompletionItem> getCompletions(@NonNull String prefix, String currentFile,
                                               int line, int column,
                                               String memberOf, String currentContent) {
        if (!isBlank(memberOf)) {
            return getMemberCompletions(
                trimToSize(memberOf), currentContent, line,
                resolveCurrentSymbols(currentFile), prefix);
        }
        return getCompletions(prefix == null ? "" : prefix, currentFile, line, column);
    }

    private List<CompletionItem> getMemberCompletions(String receiver, String content,
                                                      int cursorLine, FileSymbols symbols,
                                                      String prefix) {
        Map<String, String[][]> table = MemberCatalog.ALL;
        String type = resolveReceiverType(receiver, content, cursorLine, symbols, table);
        if (type == null) {
            type = MemberCatalog.knows(receiver) ? receiver : null;
        }
        if (type == null || !MemberCatalog.knows(type)) {
            return java.util.Collections.emptyList();
        }
        // Cached objects: only the filtered list is new, never the items.
        List<CompletionItem> all = MemberCatalog.membersFor(type);
        List<CompletionItem> out = new ArrayList<>(Math.min(all.size(), 32));
        for (CompletionItem item : all) {
            if (prefix == null || prefix.isEmpty()
                || startsWithIgnoreCase(item.getLabel(), prefix)) {
                out.add(item);
            }
        }
        return out;
    }


    private String resolveReceiverType(String receiver, String content, int cursorLine,
                                       FileSymbols symbols, Map<String, String[][]> table) {
        if (isBlank(receiver)) return null;
        receiver = trimToSize(receiver);
        if (table.containsKey(receiver)) return receiver;

        String lastSeg = receiver;
        int dot = lastSeg.lastIndexOf('.');
        if (dot >= 0) lastSeg = lastSeg.substring(dot + 1);
        if (table.containsKey(lastSeg)) return lastSeg;

        if (content != null && !content.isEmpty()) {
            // Walk lines backwards WITHOUT content.split("\n"): a completion
            // request must not allocate one String per line in the file.
            int lineStart = lineStartOffset(content, cursorLine);
            int lineEnd = content.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = content.length();
            while (true) {
                int hit = content.indexOf(lastSeg, lineStart);
                if (hit >= 0 && hit < lineEnd) {
                    String line = content.substring(lineStart, lineEnd);
                    Matcher m = LOCAL_DECL_PATTERN.matcher(line);
                    if (m.find()) {
                        String declared = stripType(m.group(1));
                        if (declared.equalsIgnoreCase(stripType(lastSeg))
                            || m.group(2).equals(lastSeg)) {
                            return declared;
                        }
                    }
                    m = FOR_EACH_PATTERN.matcher(line);
                    if (m.find() && m.group(2).equals(lastSeg)) {
                        return stripType(m.group(1));
                    }
                    m = PARAM_PATTERN.matcher(line);
                    while (m.find()) {
                        if (m.group(2).equals(lastSeg)) {
                            return stripType(m.group(1));
                        }
                    }
                }
                if (lineStart == 0) break;
                lineEnd = lineStart - 1;
                lineStart = content.lastIndexOf('\n', lineEnd - 1) + 1;
            }
        }

        if (symbols != null) {
            for (FieldSymbol field : symbols.fields) {
                if (field.name.equals(lastSeg)) {
                    return stripType(field.type);
                }
            }
        }
        return null;
    }

    private static final Pattern LOCAL_DECL_PATTERN = Pattern.compile(
        "^\\s*(?:final\\s+)?([A-Za-z_$][\\w$.]*(?:\\s*<[^=;]*>)?(?:\\s*\\[\\s*\\])?)\\s+([A-Za-z_$][\\w$]*)\\s*(?:=[^=].*)?[;,]?");
    private static final Pattern FOR_EACH_PATTERN = Pattern.compile(
        "\\bfor\\s*\\(\\s*([A-Za-z_$][\\w$.]*(?:\\s*<[^>]*>)?(?:\\s*\\[\\s*\\])?)\\s+([A-Za-z_$][\\w$]*)\\s*:");
    private static final Pattern PARAM_PATTERN = Pattern.compile(
        "[({]\\s*([A-Za-z_$][\\w$]*(?:\\s*<[^>]*>)?(?:\\s*\\[\\s*\\])?)\\s+([A-Za-z_$][\\w$]*)\\s*(?=[,)])");

    /**
     * Normalises a declared type to a receiver type without per-call regex:
     * strips whitespace, array brackets and generics, then takes the simple name.
     * Allocates only when the input actually needs cleaning.
     */
    private static String stripType(String raw) {
        if (raw == null) return "";
        int start = 0;
        int end = raw.length();
        while (start < end && raw.charAt(start) <= ' ') start++;
        while (end > start && raw.charAt(end - 1) <= ' ') end--;
        if (start >= end) return "";

        StringBuilder sb = null;
        int depth = 0;
        int last = start;
        for (int i = start; i < end; i++) {
            char c = raw.charAt(i);
            if (c == '<') {
                if (depth++ == 0) {
                    if (sb == null) sb = new StringBuilder(end - start);
                    sb.append(raw, last, i);
                }
            } else if (c == '>') {
                if (depth > 0) depth--;
                if (depth == 0) last = i + 1;
            } else if (depth == 0 && (c == '[' || c == ']' || c == ' ')) {
                if (sb == null) sb = new StringBuilder(end - start);
                sb.append(raw, last, i);
                last = i + 1;
            }
        }
        String t;
        if (sb != null) {
            t = sb.append(raw, last, end).toString();
        } else if (start == 0 && end == raw.length()) {
            t = raw;
        } else {
            t = raw.substring(start, end);
        }
        int dot = t.lastIndexOf('.');
        if (dot >= 0) t = t.substring(dot + 1);
        switch (t) {
            case "int": return "Integer";
            case "long": return "Long";
            case "double": return "Double";
            case "float": return "Float";
            case "boolean": return "Boolean";
            case "char": return "Character";
            default: return t;
        }
    }

    /** True when the string is empty or whitespace-only, without allocating a trimmed copy. */
    private static boolean isBlank(String s) {
        if (s == null) return true;
        for (int i = 0, n = s.length(); i < n; i++) {
            if (s.charAt(i) > ' ') return false;
        }
        return true;
    }

    /** Returns the same instance when no trim is needed; allocates only when it is. */
    private static String trimToSize(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) <= ' ') start++;
        while (end > start && s.charAt(end - 1) <= ' ') end--;
        if (start == 0 && end == s.length()) return s;
        return s.substring(start, end);
    }

    private void addCompletionsFromSymbols(List<CompletionItem> completions, Set<String> seen,
            FileSymbols symbols, String prefix, boolean accessible) {
        if (!accessible) return;

        for (ClassSymbol cls : symbols.classes) {
            if (startsWithIgnoreCase(cls.name, prefix) && seen.add(cls.name)) {
                CompletionItem item = new CompletionItem(cls.name, CompletionItem.Kind.CLASS);
                item.setDetail(cls.type + " in " + symbols.fileName);
                item.setSortPriority(10);
                completions.add(item);
            }
        }

        for (MethodSymbol method : symbols.methods) {
            if (startsWithIgnoreCase(method.name, prefix) && seen.add(method.name)) {
                CompletionItem item = new CompletionItem(method.name, CompletionItem.Kind.METHOD);
                item.setDetail(method.signature);
                item.setInsertText(method.name + "()");
                item.setSortPriority(20);
                completions.add(item);
            }
        }

        for (FieldSymbol field : symbols.fields) {
            if (startsWithIgnoreCase(field.name, prefix) && seen.add(field.name)) {
                CompletionItem item = new CompletionItem(field.name, CompletionItem.Kind.FIELD);
                item.setDetail(field.type + " in " + symbols.fileName);
                item.setSortPriority(30);
                completions.add(item);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void addStdlibCompletions(List<CompletionItem> completions, Set<String> seen,
                                      Set<String> imports, String prefix) {
        for (Map.Entry<String, List<CompletionItem>> entry : stdlibIndex.entrySet()) {
            String packageName = entry.getKey();

            if (!matchesImport(imports, packageName)
                && !packageName.startsWith("java.lang")) {
                continue;
            }

            for (CompletionItem item : entry.getValue()) {
                if (startsWithIgnoreCase(item.getLabel(), prefix) && seen.add(item.getLabel())) {
                    CompletionItem copy = new CompletionItem(item.getLabel(), item.getKind());
                    copy.setDetail(item.getDetail());
                    copy.setDocumentation(item.getDocumentation());
                    copy.setInsertText(item.getInsertText());
                    copy.setSortPriority(item.getSortPriority() + 100);
                    completions.add(copy);
                }
            }
        }
    }

    private void addKeywordsAndSnippets(List<CompletionItem> completions, String prefix) {
        for (String[] kw : KEYWORDS) {
            if (startsWithIgnoreCase(kw[0], prefix)) {
                CompletionItem item = new CompletionItem(kw[0], CompletionItem.Kind.SNIPPET);
                item.setDetail(kw[1]);
                item.setInsertText(kw[2]);
                item.setSortPriority(1000);
                completions.add(item);
            }
        }
    }

    private boolean isAccessible(FileSymbols symbols, Set<String> imports) {
        if (symbols.packageName == null || symbols.packageName.isEmpty()) {
            return true;
        }
        return matchesImport(imports, symbols.packageName);
    }

    /** Allocation-free prefix test (no per-call toLowerCase()). */
    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return prefix == null || prefix.isEmpty()
            || (value != null && value.regionMatches(true, 0, prefix, 0, prefix.length()));
    }

    /** {@code pkg}, {@code pkg.*}, and {@code pkg.Class} all match. */
    private static boolean matchesImport(Set<String> imports, String packageName) {
        for (String imp : imports) {
            if (imp.equals(packageName)) return true;
            if (imp.length() > packageName.length() && imp.startsWith(packageName)
                && imp.charAt(packageName.length()) == '.') {
                return true;
            }
        }
        return false;
    }

    /** Offset of the first character of a 1-based line, no splitting. */
    private static int lineStartOffset(String content, int line) {
        if (line <= 1) return 0;
        int current = 1;
        int offset = 0;
        while (current < line) {
            int nl = content.indexOf('\n', offset);
            if (nl < 0) return content.length();
            offset = nl + 1;
            current++;
        }
        return offset;
    }

    private String extractPackage(String content) {
        Matcher m = PACKAGE_PATTERN.matcher(content);
        return m.find() ? m.group(1) : "";
    }

    private Set<String> extractImports(String content) {
        Set<String> imports = new HashSet<>();
        Matcher m = IMPORT_PATTERN.matcher(content);
        while (m.find()) {
            imports.add(m.group(1));
        }
        return imports;
    }

    private List<ClassSymbol> extractClasses(String content) {
        List<ClassSymbol> classes = new ArrayList<>();
        Matcher m = CLASS_PATTERN.matcher(content);
        while (m.find()) {
            String keyword = content.substring(Math.max(0, m.start() - 20), m.start()).trim();
            String type = "class";
            if (keyword.contains("interface")) type = "interface";
            else if (keyword.contains("enum")) type = "enum";
            else if (keyword.contains("record")) type = "record";
            else if (keyword.contains("@interface")) type = "annotation";

            classes.add(new ClassSymbol(m.group(1), type));
        }
        return classes;
    }

    private List<MethodSymbol> extractMethods(String content) {
        List<MethodSymbol> methods = new ArrayList<>();
        Matcher m = METHOD_PATTERN.matcher(content);
        while (m.find()) {
            String returnType = m.group(1);
            String name = m.group(2);
            if (!name.matches("^(if|for|while|switch|catch|synchronized)$")) {
                methods.add(new MethodSymbol(name, returnType + " " + name + "()"));
            }
        }
        return methods;
    }

    private List<FieldSymbol> extractFields(String content) {
        List<FieldSymbol> fields = new ArrayList<>();
        Matcher m = FIELD_PATTERN.matcher(content);
        while (m.find()) {
            String name = m.group(1);
            if (!name.matches("^(this|super|return|throw|new)$")) {
                fields.add(new FieldSymbol(name, "field"));
            }
        }
        return fields;
    }

    private void buildStdlibIndex() {
        addStdlibClass("java.lang", "String", "class", "java.lang.String",
            "String text = \"hello\";");
        addStdlibClass("java.lang", "Object", "class", "java.lang.Object");
        addStdlibClass("java.lang", "System", "class", "java.lang.System");
        addStdlibClass("java.lang", "Math", "class", "java.lang.Math");
        addStdlibClass("java.lang", "Integer", "class", "java.lang.Integer");
        addStdlibClass("java.lang", "Long", "class", "java.lang.Long");
        addStdlibClass("java.lang", "Double", "class", "java.lang.Double");
        addStdlibClass("java.lang", "Boolean", "class", "java.lang.Boolean");
        addStdlibClass("java.lang", "Character", "class", "java.lang.Character");
        addStdlibClass("java.lang", "Exception", "class", "java.lang.Exception");
        addStdlibClass("java.lang", "RuntimeException", "class", "java.lang.RuntimeException");
        addStdlibClass("java.lang", "Thread", "class", "java.lang.Thread");
        addStdlibClass("java.lang", "Runnable", "interface", "java.lang.Runnable");
        addStdlibClass("java.lang", "Comparable", "interface", "java.lang.Comparable");
        addStdlibClass("java.lang", "StringBuilder", "class", "java.lang.StringBuilder");
        addStdlibClass("java.lang", "StringBuffer", "class", "java.lang.StringBuffer");

        addStdlibClass("java.util", "ArrayList", "class", "java.util.ArrayList",
            "List<String> list = new ArrayList<>();");
        addStdlibClass("java.util", "LinkedList", "class", "java.util.LinkedList");
        addStdlibClass("java.util", "HashMap", "class", "java.util.HashMap",
            "Map<String, Integer> map = new HashMap<>();");
        addStdlibClass("java.util", "HashSet", "class", "java.util.HashSet");
        addStdlibClass("java.util", "TreeSet", "class", "java.util.TreeSet");
        addStdlibClass("java.util", "List", "interface", "java.util.List");
        addStdlibClass("java.util", "Set", "interface", "java.util.Set");
        addStdlibClass("java.util", "Map", "interface", "java.util.Map");
        addStdlibClass("java.util", "Collection", "interface", "java.util.Collection");
        addStdlibClass("java.util", "Iterator", "interface", "java.util.Iterator");
        addStdlibClass("java.util", "Stream", "interface", "java.util.stream.Stream");
        addStdlibClass("java.util", "Optional", "class", "java.util.Optional");
        addStdlibClass("java.util", "Collections", "class", "java.util.Collections");
        addStdlibClass("java.util", "Arrays", "class", "java.util.Arrays");
        addStdlibClass("java.util", "Date", "class", "java.util.Date");
        addStdlibClass("java.util", "Calendar", "class", "java.util.Calendar");
        addStdlibClass("java.util", "Random", "class", "java.util.Random");
        addStdlibClass("java.util", "UUID", "class", "java.util.UUID");
        addStdlibClass("java.util", "Properties", "class", "java.util.Properties");

        addStdlibClass("java.io", "File", "class", "java.io.File",
            "File file = new File(\"path\");");
        addStdlibClass("java.io", "FileInputStream", "class", "java.io.FileInputStream");
        addStdlibClass("java.io", "FileOutputStream", "class", "java.io.FileOutputStream");
        addStdlibClass("java.io", "FileReader", "class", "java.io.FileReader");
        addStdlibClass("java.io", "FileWriter", "class", "java.io.FileWriter",
            "try (FileWriter w = new FileWriter(file)) { w.write(text); }");
        addStdlibClass("java.io", "BufferedReader", "class", "java.io.BufferedReader",
            "try (BufferedReader r = new BufferedReader(new FileReader(file))) { String line; }");
        addStdlibClass("java.io", "BufferedWriter", "class", "java.io.BufferedWriter");
        addStdlibClass("java.io", "InputStream", "class", "java.io.InputStream");
        addStdlibClass("java.io", "OutputStream", "class", "java.io.OutputStream");
        addStdlibClass("java.io", "Reader", "class", "java.io.Reader");
        addStdlibClass("java.io", "Writer", "class", "java.io.Writer");
        addStdlibClass("java.io", "PrintWriter", "class", "java.io.PrintWriter");
        addStdlibClass("java.io", "Serializable", "interface", "java.io.Serializable");
        addStdlibClass("java.io", "IOException", "class", "java.io.IOException");

        addStdlibClass("java.nio.file", "Files", "class", "java.nio.file.Files",
            "Files.writeString(path, text);");
        addStdlibClass("java.nio.file", "Paths", "class", "java.nio.file.Paths",
            "Path path = Paths.get(\"file.txt\");");
        addStdlibClass("java.nio.file", "Path", "interface", "java.nio.file.Path");
        addStdlibClass("java.nio.file", "StandardOpenOption", "enum", "java.nio.file.StandardOpenOption");
        addStdlibClass("java.nio.charset", "StandardCharsets", "class", "java.nio.charset.StandardCharsets");

        addStdlibClass("java.net", "URL", "class", "java.net.URL");
        addStdlibClass("java.net", "URI", "class", "java.net.URI");
        addStdlibClass("java.net", "HttpURLConnection", "class", "java.net.HttpURLConnection");
        addStdlibClass("java.net", "Socket", "class", "java.net.Socket");
        addStdlibClass("java.net", "ServerSocket", "class", "java.net.ServerSocket",
            "ServerSocket server = new ServerSocket(8080);");
        addStdlibClass("java.net", "InetAddress", "class", "java.net.InetAddress");
        addStdlibClass("java.net", "DatagramSocket", "class", "java.net.DatagramSocket");

        addStdlibClass("java.time", "LocalDate", "class", "java.time.LocalDate");
        addStdlibClass("java.time", "LocalTime", "class", "java.time.LocalTime");
        addStdlibClass("java.time", "LocalDateTime", "class", "java.time.LocalDateTime");
        addStdlibClass("java.time", "Instant", "class", "java.time.Instant");
        addStdlibClass("java.time", "Duration", "class", "java.time.Duration");
        addStdlibClass("java.time", "Period", "class", "java.time.Period");
        addStdlibClass("java.time", "ZoneId", "class", "java.time.ZoneId");
        addStdlibClass("java.time", "DateTimeFormatter", "class", "java.time.format.DateTimeFormatter");

        addStdlibClass("java.util.concurrent", "ExecutorService", "interface", "java.util.concurrent.ExecutorService");
        addStdlibClass("java.util.concurrent", "Executors", "class", "java.util.concurrent.Executors");
        addStdlibClass("java.util.concurrent", "Future", "interface", "java.util.concurrent.Future");
        addStdlibClass("java.util.concurrent", "CompletableFuture", "class", "java.util.concurrent.CompletableFuture");
        addStdlibClass("java.util.concurrent", "CountDownLatch", "class", "java.util.concurrent.CountDownLatch");
        addStdlibClass("java.util.concurrent", "CyclicBarrier", "class", "java.util.concurrent.CyclicBarrier");
        addStdlibClass("java.util.concurrent", "Semaphore", "class", "java.util.concurrent.Semaphore");
        addStdlibClass("java.util.concurrent", "ConcurrentHashMap", "class", "java.util.concurrent.ConcurrentHashMap");
        addStdlibClass("java.util.concurrent", "BlockingQueue", "interface", "java.util.concurrent.BlockingQueue");
        addStdlibClass("java.util.concurrent", "LinkedBlockingQueue", "class", "java.util.concurrent.LinkedBlockingQueue");
        addStdlibClass("java.util.concurrent", "ThreadPoolExecutor", "class", "java.util.concurrent.ThreadPoolExecutor");
        addStdlibClass("java.util.concurrent", "TimeUnit", "enum", "java.util.concurrent.TimeUnit");

        addStdlibClass("java.util.function", "Function", "interface", "java.util.function.Function");
        addStdlibClass("java.util.function", "Predicate", "interface", "java.util.function.Predicate");
        addStdlibClass("java.util.function", "Consumer", "interface", "java.util.function.Consumer");
        addStdlibClass("java.util.function", "Supplier", "interface", "java.util.function.Supplier");
        addStdlibClass("java.util.function", "BinaryOperator", "interface", "java.util.function.BinaryOperator");
        addStdlibClass("java.util.function", "UnaryOperator", "interface", "java.util.function.UnaryOperator");
    }

    private void addStdlibClass(String packageName, String className, String type, String detail) {
        addStdlibClass(packageName, className, type, detail, null);
    }

    private void addStdlibClass(String packageName, String className, String type, String detail, String snippet) {
        CompletionItem.Kind kind = "interface".equals(type) ? CompletionItem.Kind.INTERFACE :
            "enum".equals(type) ? CompletionItem.Kind.ENUM :
            "annotation".equals(type) ? CompletionItem.Kind.ANNOTATION :
            CompletionItem.Kind.CLASS;

        CompletionItem item = new CompletionItem(className, kind);
        item.setDetail(detail);
        if (snippet != null) {
            item.setInsertText(snippet);
        }
        item.setSortPriority(50);

        stdlibIndex.computeIfAbsent(packageName, k -> new ArrayList<>()).add(item);
        if (!classToPackage.containsKey(className)) {
            classToPackage.put(className, packageName);
        }
    }

    private static class FileSymbols {
        String fileName;
        String packageName;
        Set<String> imports = new HashSet<>();
        List<ClassSymbol> classes = new ArrayList<>();
        List<MethodSymbol> methods = new ArrayList<>();
        List<FieldSymbol> fields = new ArrayList<>();
    }

    private static class ClassSymbol {
        String name;
        String type;
        ClassSymbol(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    private static class MethodSymbol {
        String name;
        String signature;
        MethodSymbol(String name, String signature) {
            this.name = name;
            this.signature = signature;
        }
    }

    private static class FieldSymbol {
        String name;
        String type;
        FieldSymbol(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }
}
