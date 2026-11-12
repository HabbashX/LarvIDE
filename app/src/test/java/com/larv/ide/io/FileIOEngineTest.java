package com.larv.ide.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class FileIOEngineTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void roundTripsUtf8IncludingArabic() throws IOException {
        File file = folder.newFile("text.txt");
        String content = "hello\nمرحبا بالعالم\npublic class Main { }\n";
        FileWriter.write(file, content);
        assertEquals(content, FileReader.read(file));
    }

    @Test
    public void detectsUtf8BomWithoutLeakingIt() throws IOException {
        File file = folder.newFile("bom.txt");
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        Files.write(file.toPath(), withBom);
        assertEquals("abc", FileReader.read(file));
    }

    @Test
    public void fallsBackLosslesslyForInvalidUtf8() throws IOException {
        File file = folder.newFile("latin.txt");
        byte[] latin = new byte[] { 'c', 'a', 'f', (byte) 0xE9 }; // "café" in Latin-1
        Files.write(file.toPath(), latin);
        String decoded = FileReader.read(file);
        assertTrue(decoded.startsWith("caf"));
        assertEquals(4, decoded.length());
    }

    @Test
    public void rejectsFilesOverTheEditorCap() throws IOException {
        File file = folder.newFile("big.txt");
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw")) {
            raf.setLength(FileReader.MAX_BYTES + 1L);
        }
        try {
            FileReader.read(file);
            throw new AssertionError("expected IOException for oversized file");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("too large"));
        }
    }

    @Test
    public void atomicWriterReplacesContentWithoutLeavingTempFile() throws IOException {
        File file = folder.newFile("Main.java");
        FileWriter.write(file, "old");
        AtomicFileWriter.write(file, "new content");
        assertEquals("new content", FileReader.read(file));
        assertFalse(new File(file.getParentFile(), file.getName() + ".tmp").exists());
    }

    @Test
    public void atomicWriterCreatesMissingParentDirectories() throws IOException {
        File nested = new File(folder.getRoot(), "a/b/c.txt");
        AtomicFileWriter.write(nested, "x");
        assertEquals("x", FileReader.read(nested));
    }

    @Test
    public void fileCacheIsBoundedByWeight() {
        FileCache cache = new FileCache(10, 60);
        cache.put("a", "12345");
        cache.put("b", "12345");
        assertNull(cache.get("a"));
        assertEquals("12345", cache.get("b"));
        assertTrue(cache.weight() <= 60);
    }

    @Test
    public void appendModePreservesExistingContent() throws IOException {
        File file = folder.newFile("log.txt");
        FileWriter.write(file, "one\n");
        FileWriter.write(file, "two\n", true);
        assertEquals("one\ntwo\n", FileReader.read(file));
    }
}
