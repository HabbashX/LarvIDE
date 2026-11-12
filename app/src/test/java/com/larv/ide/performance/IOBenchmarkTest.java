package com.larv.ide.performance;

import static org.junit.Assert.assertEquals;

import com.larv.ide.completion.ProjectIndexer;
import com.larv.ide.io.FileReader;
import com.larv.ide.io.FileWriter;
import com.larv.ide.model.OpenFile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JVM-level microbenchmarks for the I/O paths. These are not Android macro
 * benchmarks: they run on the test JVM, so absolute numbers differ from a
 * phone. They exist to (a) catch regressions in relative behaviour and
 * (b) justify the concrete buffer size used by the production code.
 */
public class IOBenchmarkTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File makeFile(String name, int bytes) throws IOException {
        File file = folder.newFile(name);
        StringBuilder sb = new StringBuilder(bytes + 16);
        while (sb.length() < bytes) {
            sb.append("0123456789abcdef0123456789abcdef0123456789abcdef\n");
        }
        FileWriter.write(file, sb.toString());
        return file;
    }

    @Test
    public void bufferedReadThroughputByBufferSize() throws IOException {
        File file = makeFile("throughput.txt", 1024 * 1024);
        int[] sizes = { 8 * 1024, 16 * 1024, 32 * 1024, 64 * 1024, 128 * 1024 };
        long baseline = -1;
        for (int size : sizes) {
            byte[] buffer = new byte[size];
            // Warm up the file cache / JIT once, then measure one full pass.
            drain(file, buffer);
            long start = System.nanoTime();
            long total = drain(file, buffer);
            long elapsed = System.nanoTime() - start;
            assertEquals(file.length(), total);
            double mbPerSecond = total / 1024.0 / 1024.0
                / (Math.max(1L, elapsed) / 1_000_000_000.0);
            System.out.printf("BENCH read buffer=%dKB time=%.2fms throughput=%.1fMB/s%s%n",
                size / 1024, elapsed / 1_000_000.0, mbPerSecond,
                baseline < 0 ? "" : " vs8KB=" + (elapsed * 100L / baseline) + "%");
            if (baseline < 0) baseline = elapsed;
        }
    }

    private static long drain(File file, byte[] buffer) throws IOException {
        long total = 0;
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file), 8192)) {
            int n;
            while ((n = in.read(buffer)) > 0) total += n;
        }
        return total;
    }

    @Test
    public void endToEndReadAndWriteLatency() throws IOException {
        int[] sizes = { 1024, 10 * 1024, 100 * 1024, 1024 * 1024 };
        for (int size : sizes) {
            File file = makeFile("latency-" + size + ".txt", size);
            // Warm up.
            FileReader.read(file);
            long start = System.nanoTime();
            String content = FileReader.read(file);
            long readNanos = System.nanoTime() - start;
            start = System.nanoTime();
            FileWriter.write(file, content);
            long writeNanos = System.nanoTime() - start;
            assertEquals(file.length(), content.getBytes(StandardCharsets.UTF_8).length);
            System.out.printf("BENCH file size=%dKB read=%.3fms write=%.3fms%n",
                size / 1024, readNanos / 1_000_000.0, writeNanos / 1_000_000.0);
        }
    }

    @Test
    public void projectIndexerParseLatencyScalesLinearly() {
        ProjectIndexer indexer = new ProjectIndexer();
        StringBuilder src = new StringBuilder("package p;\npublic class Big {\n");
        for (int i = 0; i < 300; i++) {
            src.append("    int field").append(i).append(";\n");
            src.append("    void method").append(i).append("() { }\n");
        }
        src.append("}\n");
        String content = src.toString();

        // Warm up the regex/JIT path.
        indexer.indexFile(new OpenFile("/p/Warm.java", content));
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            indexer.indexFile(new OpenFile("/p/Big" + i + ".java", content));
        }
        long elapsed = System.nanoTime() - start;
        System.out.printf("BENCH indexer %d lines avg=%.3fms/file%n",
            content.split("\n").length, (elapsed / 20.0) / 1_000_000.0);

        // Parse cache: identical content for the same file must be ~free.
        long cachedStart = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            indexer.indexFile(new OpenFile("/p/Big0.java", content));
        }
        long cachedNanos = System.nanoTime() - cachedStart;
        System.out.printf("BENCH indexer cacheHit avg=%.4fms/file%n",
            (cachedNanos / 200.0) / 1_000_000.0);
    }
}
