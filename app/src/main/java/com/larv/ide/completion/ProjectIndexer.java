package com.larv.ide.completion;

import android.os.Build;

import androidx.annotation.RequiresApi;

import com.larv.ide.model.OpenFile;

import java.util.ArrayList;
import java.util.HashMap;
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

    public ProjectIndexer() {
        buildStdlibIndex();
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

    @RequiresApi(api = Build.VERSION_CODES.N)
    public List<CompletionItem> getCompletions(String prefix, String currentFile, int line, int column) {
        List<CompletionItem> completions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String prefixLower = prefix.toLowerCase();

        FileSymbols currentSymbols = fileIndex.get(currentFile);
        Set<String> currentImports = currentSymbols != null ? currentSymbols.imports : new HashSet<>();

        // 1. Local symbols from current file
        if (currentSymbols != null) {
            addCompletionsFromSymbols(completions, seen, currentSymbols, prefixLower, true);
        }

        // 2. Symbols from other open files (respecting imports)
        for (Map.Entry<String, FileSymbols> entry : fileIndex.entrySet()) {
            if (!entry.getKey().equals(currentFile)) {
                addCompletionsFromSymbols(completions, seen, entry.getValue(), prefixLower,
                    isAccessible(entry.getValue(), currentImports));
            }
        }

        // 3. Stdlib completions (filtered by imports)
        addStdlibCompletions(completions, seen, currentImports, prefixLower);

        // 4. Keywords and snippets
        addKeywordsAndSnippets(completions, prefixLower);
        
        // Sort by priority
        completions.sort((a, b) -> {
            int priorityDiff = Integer.compare(a.getSortPriority(), b.getSortPriority());
            if (priorityDiff != 0) return priorityDiff;
            return a.getKindOrder() - b.getKindOrder();
        });

        return completions;
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
            return true; // Default package
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
        // java.lang
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
        
        // java.util
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
        
        // java.io
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
        
        // java.nio
        addStdlibClass("java.nio.file", "Files", "class", "java.nio.file.Files", 
            "Files.writeString(path, text);");
        addStdlibClass("java.nio.file", "Paths", "class", "java.nio.file.Paths", 
            "Path path = Paths.get(\"file.txt\");");
        addStdlibClass("java.nio.file", "Path", "interface", "java.nio.file.Path");
        addStdlibClass("java.nio.file", "StandardOpenOption", "enum", "java.nio.file.StandardOpenOption");
        addStdlibClass("java.nio.charset", "StandardCharsets", "class", "java.nio.charset.StandardCharsets");
        
        // java.net
        addStdlibClass("java.net", "URL", "class", "java.net.URL");
        addStdlibClass("java.net", "URI", "class", "java.net.URI");
        addStdlibClass("java.net", "HttpURLConnection", "class", "java.net.HttpURLConnection");
        addStdlibClass("java.net", "Socket", "class", "java.net.Socket");
        addStdlibClass("java.net", "ServerSocket", "class", "java.net.ServerSocket", 
            "ServerSocket server = new ServerSocket(8080);");
        addStdlibClass("java.net", "InetAddress", "class", "java.net.InetAddress");
        addStdlibClass("java.net", "DatagramSocket", "class", "java.net.DatagramSocket");
        
        // java.time
        addStdlibClass("java.time", "LocalDate", "class", "java.time.LocalDate");
        addStdlibClass("java.time", "LocalTime", "class", "java.time.LocalTime");
        addStdlibClass("java.time", "LocalDateTime", "class", "java.time.LocalDateTime");
        addStdlibClass("java.time", "Instant", "class", "java.time.Instant");
        addStdlibClass("java.time", "Duration", "class", "java.time.Duration");
        addStdlibClass("java.time", "Period", "class", "java.time.Period");
        addStdlibClass("java.time", "ZoneId", "class", "java.time.ZoneId");
        addStdlibClass("java.time", "DateTimeFormatter", "class", "java.time.format.DateTimeFormatter");
        
        // java.util.concurrent
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
        
        // java.util.function
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