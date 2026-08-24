package com.larv.ide.completion;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.larv.ide.model.OpenFile;

import java.util.ArrayList;
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
    private volatile Map<String, List<CompletionItem>> memberCache;

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
        List<CompletionItem> completions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String prefixLower = prefix.toLowerCase();

        FileSymbols currentSymbols = resolveCurrentSymbols(currentFile);
        Set<String> currentImports = currentSymbols != null ? currentSymbols.imports : new HashSet<>();

        if (currentSymbols != null) {
            addCompletionsFromSymbols(completions, seen, currentSymbols, prefixLower, true);
        }

        for (Map.Entry<String, FileSymbols> entry : fileIndex.entrySet()) {
            if (!entry.getKey().equals(currentFile)) {
                addCompletionsFromSymbols(completions, seen, entry.getValue(), prefixLower,
                    isAccessible(entry.getValue(), currentImports));
            }
        }

        addStdlibCompletions(completions, seen, currentImports, prefixLower);

        addKeywordsAndSnippets(completions, prefixLower);

        completions.sort((a, b) -> {
            int priorityDiff = Integer.compare(a.getSortPriority(), b.getSortPriority());
            if (priorityDiff != 0) return priorityDiff;
            return a.getKindOrder() - b.getKindOrder();
        });

        return completions;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public List<CompletionItem> getCompletions(@NonNull String prefix, String currentFile,
                                               int line, int column,
                                               String memberOf, String currentContent) {
        if (memberOf != null && !memberOf.trim().isEmpty()) {
            List<CompletionItem> members = getMemberCompletions(
                memberOf.trim(), currentContent, line, resolveCurrentSymbols(currentFile));
            if (prefix != null && !prefix.isEmpty()) {
                String p = prefix.toLowerCase();
                members.removeIf(i -> !i.getLabel().toLowerCase().startsWith(p));
            }
            return members;
        }
        return getCompletions(prefix == null ? "" : prefix, currentFile, line, column);
    }

    private static final Map<String, String[][]> MEMBER_TABLE = buildMemberTable();

    private static Map<String, String[][]> buildMemberTable() {
        Map<String, String[][]> t = new java.util.HashMap<>();
        t.put("String", new String[][]{
            {"length", "int length()", "m"},
            {"isEmpty", "boolean isEmpty()", "m"},
            {"isBlank", "boolean isBlank()", "m"},
            {"charAt", "char charAt(int index)", "m"},
            {"substring", "String substring(int beginIndex)", "m"},
            {"indexOf", "int indexOf(String str)", "m"},
            {"lastIndexOf", "int lastIndexOf(String str)", "m"},
            {"contains", "boolean contains(CharSequence s)", "m"},
            {"equals", "boolean equals(Object anObject)", "m"},
            {"equalsIgnoreCase", "boolean equalsIgnoreCase(String another)", "m"},
            {"compareTo", "int compareTo(String anotherString)", "m"},
            {"toUpperCase", "String toUpperCase()", "m"},
            {"toLowerCase", "String toLowerCase()", "m"},
            {"trim", "String trim()", "m"},
            {"strip", "String strip()", "m"},
            {"replace", "String replace(char oldChar, char newChar)", "m"},
            {"replaceAll", "String replaceAll(String regex, String replacement)", "m"},
            {"split", "String[] split(String regex)", "m"},
            {"startsWith", "boolean startsWith(String prefix)", "m"},
            {"endsWith", "boolean endsWith(String suffix)", "m"},
            {"concat", "String concat(String str)", "m"},
            {"repeat", "String repeat(int count)", "m"},
            {"matches", "boolean matches(String regex)", "m"},
            {"toCharArray", "char[] toCharArray()", "m"},
            {"getBytes", "byte[] getBytes()", "m"},
            {"format", "static String format(String fmt, Object... args)", "m"},
            {"valueOf", "static String valueOf(Object obj)", "m"},
            {"join", "static String join(CharSequence delim, CharSequence... elems)", "m"},
            {"hashCode", "int hashCode()", "m"}
        });
        t.put("StringBuilder", new String[][]{
            {"append", "StringBuilder append(String str)", "m"},
            {"insert", "StringBuilder insert(int offset, String str)", "m"},
            {"delete", "StringBuilder delete(int start, int end)", "m"},
            {"deleteCharAt", "StringBuilder deleteCharAt(int index)", "m"},
            {"replace", "StringBuilder replace(int start, int end, String str)", "m"},
            {"reverse", "StringBuilder reverse()", "m"},
            {"setLength", "void setLength(int newLength)", "m"},
            {"length", "int length()", "m"},
            {"charAt", "char charAt(int index)", "m"},
            {"toString", "String toString()", "m"}
        });
        t.put("System.out", new String[][]{
            {"println", "void println(Object x)", "m"},
            {"print", "void print(Object x)", "m"},
            {"printf", "PrintStream printf(String format, Object... args)", "m"},
            {"write", "void write(int b)", "m"},
            {"flush", "void flush()", "m"}
        });
        t.put("Math", new String[][]{
            {"abs", "static int abs(int a)", "m"},
            {"max", "static int max(int a, int b)", "m"},
            {"min", "static int min(int a, int b)", "m"},
            {"pow", "static double pow(double a, double b)", "m"},
            {"sqrt", "static double sqrt(double a)", "m"},
            {"cbrt", "static double cbrt(double a)", "m"},
            {"floor", "static double floor(double a)", "m"},
            {"ceil", "static double ceil(double a)", "m"},
            {"round", "static long round(double a)", "m"},
            {"random", "static double random()", "m"},
            {"signum", "static double signum(double d)", "m"},
            {"toRadians", "static double toRadians(double angdeg)", "m"},
            {"toDegrees", "static double toDegrees(double angrad)", "m"},
            {"PI", "static final double PI", "f"},
            {"E", "static final double E", "f"}
        });
        t.put("Integer", new String[][]{
            {"parseInt", "static int parseInt(String s)", "m"},
            {"valueOf", "static Integer valueOf(int i)", "m"},
            {"toString", "static String toString(int i)", "m"},
            {"compare", "static int compare(int x, int y)", "m"},
            {"max", "static int max(int a, int b)", "m"},
            {"min", "static int min(int a, int b)", "m"},
            {"bitCount", "static int bitCount(int i)", "m"},
            {"MAX_VALUE", "static final int MAX_VALUE", "f"},
            {"MIN_VALUE", "static final int MIN_VALUE", "f"}
        });
        t.put("Long", new String[][]{
            {"parseLong", "static long parseLong(String s)", "m"},
            {"valueOf", "static Long valueOf(long l)", "m"},
            {"toString", "static String toString(long i)", "m"},
            {"compare", "static int compare(long x, long y)", "m"},
            {"MAX_VALUE", "static final long MAX_VALUE", "f"},
            {"MIN_VALUE", "static final long MIN_VALUE", "f"}
        });
        t.put("Double", new String[][]{
            {"parseDouble", "static double parseDouble(String s)", "m"},
            {"valueOf", "static Double valueOf(double d)", "m"},
            {"isNaN", "static boolean isNaN(double v)", "m"},
            {"isInfinite", "static boolean isInfinite(double v)", "m"},
            {"compare", "static int compare(double d1, double d2)", "m"},
            {"MAX_VALUE", "static final double MAX_VALUE", "f"},
            {"MIN_VALUE", "static final double MIN_VALUE", "f"},
            {"POSITIVE_INFINITY", "static final double POSITIVE_INFINITY", "f"},
            {"NaN", "static final double NaN", "f"}
        });
        t.put("Float", new String[][]{
            {"parseFloat", "static float parseFloat(String s)", "m"},
            {"valueOf", "static Float valueOf(float f)", "m"},
            {"isNaN", "static boolean isNaN(float v)", "m"}
        });
        t.put("Boolean", new String[][]{
            {"parseBoolean", "static boolean parseBoolean(String s)", "m"},
            {"valueOf", "static Boolean valueOf(boolean b)", "m"},
            {"TRUE", "static final Boolean TRUE", "f"},
            {"FALSE", "static final Boolean FALSE", "f"}
        });
        t.put("Character", new String[][]{
            {"isDigit", "static boolean isDigit(char ch)", "m"},
            {"isLetter", "static boolean isLetter(char ch)", "m"},
            {"isLetterOrDigit", "static boolean isLetterOrDigit(char ch)", "m"},
            {"isWhitespace", "static boolean isWhitespace(char ch)", "m"},
            {"isUpperCase", "static boolean isUpperCase(char ch)", "m"},
            {"isLowerCase", "static boolean isLowerCase(char ch)", "m"},
            {"toUpperCase", "static char toUpperCase(char ch)", "m"},
            {"toLowerCase", "static char toLowerCase(char ch)", "m"},
            {"getNumericValue", "static int getNumericValue(char ch)", "m"}
        });
        String[][] listMembers = {
            {"add", "boolean add(E e)", "m"},
            {"get", "E get(int index)", "m"},
            {"set", "E set(int index, E element)", "m"},
            {"remove", "E remove(int index)", "m"},
            {"size", "int size()", "m"},
            {"isEmpty", "boolean isEmpty()", "m"},
            {"contains", "boolean contains(Object o)", "m"},
            {"indexOf", "int indexOf(Object o)", "m"},
            {"clear", "void clear()", "m"},
            {"addAll", "boolean addAll(Collection<? extends E> c)", "m"},
            {"removeAll", "boolean removeAll(Collection<?> c)", "m"},
            {"iterator", "Iterator<E> iterator()", "m"},
            {"forEach", "void forEach(Consumer<? super E> action)", "m"},
            {"toArray", "Object[] toArray()", "m"},
            {"sort", "void sort(Comparator<? super E> c)", "m"},
            {"stream", "Stream<E> stream()", "m"}
        };
        t.put("ArrayList", listMembers);
        t.put("List", listMembers);
        t.put("LinkedList", listMembers);
        String[][] mapMembers = {
            {"put", "V put(K key, V value)", "m"},
            {"get", "V get(Object key)", "m"},
            {"getOrDefault", "V getOrDefault(Object key, V defaultValue)", "m"},
            {"remove", "V remove(Object key)", "m"},
            {"containsKey", "boolean containsKey(Object key)", "m"},
            {"containsValue", "boolean containsValue(Object value)", "m"},
            {"keySet", "Set<K> keySet()", "m"},
            {"values", "Collection<V> values()", "m"},
            {"entrySet", "Set<Map.Entry<K,V>> entrySet()", "m"},
            {"size", "int size()", "m"},
            {"isEmpty", "boolean isEmpty()", "m"},
            {"clear", "void clear()", "m"},
            {"putAll", "void putAll(Map<? extends K,? extends V> m)", "m"},
            {"computeIfAbsent", "V computeIfAbsent(K key, Function<K,V> fn)", "m"},
            {"merge", "V merge(K key, V value, BiFunction<V,V,V> fn)", "m"},
            {"forEach", "void forEach(BiConsumer<K,V> action)", "m"}
        };
        t.put("HashMap", mapMembers);
        t.put("Map", mapMembers);
        t.put("TreeMap", mapMembers);
        String[][] setMembers = {
            {"add", "boolean add(E e)", "m"},
            {"remove", "boolean remove(Object o)", "m"},
            {"contains", "boolean contains(Object o)", "m"},
            {"size", "int size()", "m"},
            {"isEmpty", "boolean isEmpty()", "m"},
            {"clear", "void clear()", "m"},
            {"iterator", "Iterator<E> iterator()", "m"},
            {"stream", "Stream<E> stream()", "m"}
        };
        t.put("HashSet", setMembers);
        t.put("Set", setMembers);
        t.put("LinkedHashSet", setMembers);
        t.put("Arrays", new String[][]{
            {"sort", "static void sort(int[] a)", "m"},
            {"fill", "static void fill(int[] a, int val)", "m"},
            {"copyOf", "static int[] copyOf(int[] original, int newLength)", "m"},
            {"copyOfRange", "static int[] copyOfRange(int[] original, int from, int to)", "m"},
            {"asList", "static List<T> asList(T... a)", "m"},
            {"toString", "static String toString(int[] a)", "m"},
            {"deepToString", "static String deepToString(Object[] a)", "m"},
            {"equals", "static boolean equals(int[] a, int[] a2)", "m"},
            {"binarySearch", "static int binarySearch(int[] a, int key)", "m"},
            {"stream", "static IntStream stream(int[] array)", "m"}
        });
        t.put("Collections", new String[][]{
            {"sort", "static <T> void sort(List<T> list)", "m"},
            {"reverse", "static void reverse(List<?> list)", "m"},
            {"shuffle", "static void shuffle(List<?> list)", "m"},
            {"max", "static <T extends Comparable> T max(Collection coll)", "m"},
            {"min", "static <T extends Comparable> T min(Collection coll)", "m"},
            {"swap", "static void swap(List<?> list, int i, int j)", "m"},
            {"unmodifiableList", "static <T> List<T> unmodifiableList(List<? extends T> list)", "m"},
            {"emptyList", "static <T> List<T> emptyList()", "m"},
            {"singletonList", "static <T> List<T> singletonList(T o)", "m"},
            {"frequency", "static int frequency(Collection<?> c, Object o)", "m"}
        });
        t.put("Objects", new String[][]{
            {"requireNonNull", "static <T> T requireNonNull(T obj)", "m"},
            {"equals", "static boolean equals(Object a, Object b)", "m"},
            {"hash", "static int hash(Object... values)", "m"},
            {"toString", "static String toString(Object o)", "m"},
            {"isNull", "static boolean isNull(Object obj)", "m"},
            {"nonNull", "static boolean nonNull(Object obj)", "m"}
        });
        t.put("Optional", new String[][]{
            {"of", "static <T> Optional<T> of(T value)", "m"},
            {"ofNullable", "static <T> Optional<T> ofNullable(T value)", "m"},
            {"empty", "static <T> Optional<T> empty()", "m"},
            {"isPresent", "boolean isPresent()", "m"},
            {"isEmpty", "boolean isEmpty()", "m"},
            {"get", "T get()", "m"},
            {"orElse", "T orElse(T other)", "m"},
            {"orElseThrow", "T orElseThrow()", "m"},
            {"ifPresent", "void ifPresent(Consumer<? super T> action)", "m"},
            {"map", "<U> Optional<U> map(Function<? super T,? extends U> mapper)", "m"},
            {"filter", "Optional<T> filter(Predicate<? super T> predicate)", "m"}
        });
        t.put("Thread", new String[][]{
            {"start", "void start()", "m"},
            {"run", "void run()", "m"},
            {"sleep", "static void sleep(long millis)", "m"},
            {"join", "void join()", "m"},
            {"interrupt", "void interrupt()", "m"},
            {"setName", "void setName(String name)", "m"},
            {"getName", "String getName()", "m"},
            {"currentThread", "static Thread currentThread()", "m"},
            {"isAlive", "boolean isAlive()", "m"}
        });
        String[][] throwableMembers = {
            {"getMessage", "String getMessage()", "m"},
            {"getLocalizedMessage", "String getLocalizedMessage()", "m"},
            {"printStackTrace", "void printStackTrace()", "m"},
            {"getCause", "Throwable getCause()", "m"},
            {"toString", "String toString()", "m"}
        };
        t.put("Exception", throwableMembers);
        t.put("Throwable", throwableMembers);
        t.put("RuntimeException", throwableMembers);
        t.put("Iterator", new String[][]{
            {"hasNext", "boolean hasNext()", "m"},
            {"next", "E next()", "m"},
            {"remove", "default void remove()", "m"},
            {"forEachRemaining", "default void forEachRemaining(Consumer<? super E> action)", "m"}
        });
        t.put("Scanner", new String[][]{
            {"next", "String next()", "m"},
            {"nextLine", "String nextLine()", "m"},
            {"nextInt", "int nextInt()", "m"},
            {"nextDouble", "double nextDouble()", "m"},
            {"nextBoolean", "boolean nextBoolean()", "m"},
            {"hasNext", "boolean hasNext()", "m"},
            {"close", "void close()", "m"}
        });
        t.put("Random", new String[][]{
            {"nextInt", "int nextInt(int bound)", "m"},
            {"nextDouble", "double nextDouble()", "m"},
            {"nextBoolean", "boolean nextBoolean()", "m"},
            {"nextGaussian", "double nextGaussian()", "m"},
            {"nextLong", "long nextLong()", "m"},
            {"setSeed", "void setSeed(long seed)", "m"}
        });
        t.put("File", new String[][]{
            {"exists", "boolean exists()", "m"},
            {"getName", "String getName()", "m"},
            {"getPath", "String getPath()", "m"},
            {"getAbsolutePath", "String getAbsolutePath()", "m"},
            {"length", "long length()", "m"},
            {"delete", "boolean delete()", "m"},
            {"mkdir", "boolean mkdir()", "m"},
            {"mkdirs", "boolean mkdirs()", "m"},
            {"listFiles", "File[] listFiles()", "m"},
            {"isDirectory", "boolean isDirectory()", "m"},
            {"isFile", "boolean isFile()", "m"}
        });
        t.put("PrintStream", t.get("System.out"));
        return t;
    }

    private List<CompletionItem> getMemberCompletions(String receiver, String content,
                                                      int cursorLine, FileSymbols symbols) {
        Map<String, String[][]> table = MEMBER_TABLE;
        String type = resolveReceiverType(receiver, content, cursorLine, symbols, table);
        if (type == null) {
            type = table.containsKey(receiver) ? receiver : null;
        }
        List<CompletionItem> items = new ArrayList<>();
        if (type == null || !table.containsKey(type)) {
            return items;
        }
        Set<String> seen = new HashSet<>();
        for (String[] row : table.get(type)) {
            CompletionItem.Kind kind = row[2].equals("f")
                ? CompletionItem.Kind.FIELD : CompletionItem.Kind.METHOD;
            CompletionItem item = new CompletionItem(row[0], kind);
            item.setDetail(row[1]);
            item.setInsertText(row[0].endsWith("(") ? row[0] : row[0] + "()");
            item.setSortPriority(kind == CompletionItem.Kind.FIELD ? 30 : 20);
            items.add(item);
            seen.add(row[0]);
        }
        for (String[] row : OBJECT_MEMBERS) {
            if (seen.add(row[0])) {
                CompletionItem item = new CompletionItem(row[0], CompletionItem.Kind.METHOD);
                item.setDetail(row[1]);
                item.setInsertText(row[0] + "()");
                item.setSortPriority(60);
                items.add(item);
            }
        }
        return items;
    }

    private static final String[][] OBJECT_MEMBERS = {
        {"equals", "boolean equals(Object obj)"},
        {"hashCode", "int hashCode()"},
        {"toString", "String toString()"},
        {"getClass", "Class<?> getClass()"}
    };

    private String resolveReceiverType(String receiver, String content, int cursorLine,
                                       FileSymbols symbols, Map<String, String[][]> table) {
        receiver = receiver.trim();
        if (receiver.isEmpty()) return null;
        if (table.containsKey(receiver)) return receiver;

        String lastSeg = receiver;
        int dot = lastSeg.lastIndexOf('.');
        if (dot >= 0) lastSeg = lastSeg.substring(dot + 1);
        if (table.containsKey(lastSeg)) return lastSeg;

        if (content != null && !content.isEmpty()) {
            String[] lines = content.split("\n");
            int limit = Math.min(cursorLine <= 0 ? lines.length : cursorLine, lines.length);
            for (int i = limit - 1; i >= 0; i--) {
                String line = lines[i];
                Matcher m = LOCAL_DECL_PATTERN.matcher(line);
                if (m.find()) {
                    if (stripType(m.group(1)).equalsIgnoreCase(stripType(lastSeg))
                        || m.group(2).equals(lastSeg)) {
                        return stripType(m.group(1));
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
        "^\\s*(?:final\\s+)?([A-Za-z_$][\\w$]*(?:\\s*<[^=;]*>)?(?:\\s*\\[\\s*\\])?)\\s+([A-Za-z_$][\\w$]*)\\s*(?:=[^=].*)?[;,]?");
    private static final Pattern FOR_EACH_PATTERN = Pattern.compile(
        "\\bfor\\s*\\(\\s*([A-Za-z_$][\\w$.]*(?:\\s*<[^>]*>)?(?:\\s*\\[\\s*\\])?)\\s+([A-Za-z_$][\\w$]*)\\s*:");
    private static final Pattern PARAM_PATTERN = Pattern.compile(
        "[({]\\s*([A-Za-z_$][\\w$]*(?:\\s*<[^>]*>)?(?:\\s*\\[\\s*\\])?)\\s+([A-Za-z_$][\\w$]*)\\s*(?=[,)])");

    private String stripType(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        t = t.replaceAll("<[^<>]*>", "");
        t = t.replace("[", "").replace("]", "").replace(" ", "");
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

    private void addCompletionsFromSymbols(List<CompletionItem> completions, Set<String> seen,
            FileSymbols symbols, String prefixLower, boolean accessible) {
        if (!accessible) return;

        for (ClassSymbol cls : symbols.classes) {
            if (cls.name.toLowerCase().startsWith(prefixLower) && seen.add(cls.name)) {
                CompletionItem item = new CompletionItem(cls.name, CompletionItem.Kind.CLASS);
                item.setDetail(cls.type + " in " + symbols.fileName);
                item.setSortPriority(10);
                completions.add(item);
            }
        }

        for (MethodSymbol method : symbols.methods) {
            if (method.name.toLowerCase().startsWith(prefixLower) && seen.add(method.name)) {
                CompletionItem item = new CompletionItem(method.name, CompletionItem.Kind.METHOD);
                item.setDetail(method.signature);
                item.setInsertText(method.name + "()");
                item.setSortPriority(20);
                completions.add(item);
            }
        }

        for (FieldSymbol field : symbols.fields) {
            if (field.name.toLowerCase().startsWith(prefixLower) && seen.add(field.name)) {
                CompletionItem item = new CompletionItem(field.name, CompletionItem.Kind.FIELD);
                item.setDetail(field.type + " in " + symbols.fileName);
                item.setSortPriority(30);
                completions.add(item);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void addStdlibCompletions(List<CompletionItem> completions, Set<String> seen,
                                      Set<String> imports, String prefixLower) {
        for (Map.Entry<String, List<CompletionItem>> entry : stdlibIndex.entrySet()) {
            String packageName = entry.getKey();

            boolean importMatches = imports.stream().anyMatch(imp ->
                imp.equals(packageName) || imp.equals(packageName + ".*") || imp.startsWith(packageName + ".")
            );

            if (!importMatches && !packageName.startsWith("java.lang")) {
                continue;
            }

            for (CompletionItem item : entry.getValue()) {
                if (item.getLabel().toLowerCase().startsWith(prefixLower) && seen.add(item.getLabel())) {
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
        String prefixLower = prefix.toLowerCase();

        for (String[] kw : KEYWORDS) {
            if (kw[0].toLowerCase().startsWith(prefixLower)) {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return imports.stream().anyMatch(imp ->
                imp.equals(symbols.packageName) ||
                imp.equals(symbols.packageName + ".*") ||
                imp.startsWith(symbols.packageName + ".")
            );
        } else {
            return false;
        }
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
