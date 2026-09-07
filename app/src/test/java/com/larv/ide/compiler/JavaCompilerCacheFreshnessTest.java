package com.larv.ide.compiler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.larv.ide.model.OpenFile;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

/**
 * Locks in the "compile() always mirrors OpenFile content to disk" contract.
 * A skipped rewrite is how "Run prints old code" happens (stale cache copy
 * compiled instead of the edited buffer).
 */
public class JavaCompilerCacheFreshnessTest {

    private static JavaCompiler newCompiler(File base) {
        return new JavaCompiler(new File(base, "cache"), new File(base, "out"));
    }

    private static File find(File dir, String name) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File hit = find(f, name);
                if (hit != null) return hit;
            } else if (f.getName().equals(name)) {
                return f;
            }
        }
        return null;
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void recompileRewritesSourceEvenWhenContentUnchanged() throws Exception {
        File base = Files.createTempDirectory("larvjavac-rewrite").toFile();
        JavaCompiler compiler = newCompiler(base);
        String src = "public class Main {\n"
            + "    public static void main(String[] a) {\n"
            + "        System.out.println(1);\n"
            + "    }\n"
            + "}\n";
        OpenFile f = new OpenFile("/proj/Main.java", src);
        // Result ignored: no android.jar bootclasspath on the JVM, ECJ fails —
        // but the source mirror must be written regardless.
        compiler.compile(Collections.singletonList(f));
        File cached = find(new File(base, "cache"), "Main.java");
        assertNotNull("source mirror missing after first compile", cached);
        assertEquals(src, read(cached));

        // Simulate cache eviction, then recompile IDENTICAL content.
        // A write-skipping compiler leaves the file missing here.
        deleteRecursively(cached.getParentFile());
        assertFalse(cached.exists());
        compiler.compile(Collections.singletonList(f));
        assertTrue("source mirror not rewritten on identical recompile", cached.exists());
        assertEquals(src, read(cached));
    }

    @Test
    public void editedContentReachesDiskAfterTypeCheck() throws Exception {
        File base = Files.createTempDirectory("larvjavac-edit").toFile();
        JavaCompiler compiler = newCompiler(base);
        String v1 = "public class Main {\n"
            + "    public static void main(String[] a) {\n"
            + "        System.out.println(1);\n"
            + "    }\n"
            + "}\n";
        String v2 = "public class Main {\n"
            + "    public static void main(String[] a) {\n"
            + "        System.out.println(2);\n"
            + "    }\n"
            + "}\n";
        OpenFile f = new OpenFile("/proj/Main.java", v1);
        compiler.compile(Collections.singletonList(f));
        compiler.typeCheck(Collections.singletonList(f));
        f.setContent(v2);
        compiler.compile(Collections.singletonList(f));
        File cached = find(new File(base, "cache"), "Main.java");
        assertNotNull(cached);
        assertEquals(v2, read(cached));
    }
}
