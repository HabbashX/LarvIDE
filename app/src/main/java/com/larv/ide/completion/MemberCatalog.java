package com.larv.ide.completion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MemberCatalog {

    static final Map<String, String[][]> ALL = build();

    public static final String[][] OBJECT_MEMBERS = {
        {"equals", "boolean equals(Object obj)"},
        {"hashCode", "int hashCode()"},
        {"toString", "String toString()"},
        {"getClass", "Class<?> getClass()"}
    };

    private MemberCatalog() {
    }

    static List<CompletionItem> itemsFor(String type) {
        List<CompletionItem> items = new ArrayList<>();
        String[][] rows = ALL.get(type);
        if (rows == null) return items;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String[] row : rows) {
            CompletionItem item = new CompletionItem(row[0],
                row[2].equals("f") ? CompletionItem.Kind.FIELD : CompletionItem.Kind.METHOD);
            item.setDetail(row[1]);
            item.setInsertText(row[0].endsWith("(") ? row[0] : row[0] + "()");
            item.setSortPriority(row[2].equals("f") ? 30 : 20);
            if (seen.add(row[0])) {
                items.add(item);
            }
        }
        return items;
    }

    static boolean knows(String type) {
        return ALL.containsKey(type);
    }

    private static Map<String, String[][]> build() {
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
}
