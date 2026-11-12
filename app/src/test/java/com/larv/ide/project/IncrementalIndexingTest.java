package com.larv.ide.project;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.larv.ide.completion.CompletionItem;
import com.larv.ide.completion.ProjectIndexer;
import com.larv.ide.model.OpenFile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class IncrementalIndexingTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void changeTrackerDetectsContentAndSizeChanges() throws Exception {
        File file = folder.newFile("A.java");
        FileChangeTracker tracker = new FileChangeTracker();
        assertTrue(tracker.hasChanged(file));
        tracker.markIndexed(file);
        assertFalse(tracker.hasChanged(file));

        Files.write(file.toPath(), "class A { }".getBytes(StandardCharsets.UTF_8));
        file.setLastModified(file.lastModified() + 2000L);
        assertTrue(tracker.hasChanged(file));
    }

    @Test
    public void projectIndexCacheSkipsIdenticalContent() {
        ProjectIndexCache cache = new ProjectIndexCache();
        assertFalse(cache.isFresh("/p/A.java", "class A {}"));
        cache.put("/p/A.java", "class A {}");
        assertTrue(cache.isFresh("/p/A.java", "class A {}"));
        assertFalse(cache.isFresh("/p/A.java", "class A { int x; }"));
    }

    @Test
    public void indexProjectSkipsUnchangedFilesAndIgnoredDirectories() throws Exception {
        File root = folder.newFolder("proj");
        File src = new File(root, "src");
        assertTrue(src.mkdirs());
        File a = new File(src, "Alpha.java");
        Files.write(a.toPath(),
            "package p;\npublic class Alpha {\n  void alphaMethod() {}\n}"
                .getBytes(StandardCharsets.UTF_8));
        File ignored = new File(root, "build");
        assertTrue(ignored.mkdirs());
        Files.write(new File(ignored, "Ignored.java").toPath(),
            "class Ignored { void ignoredMethod() {} }".getBytes(StandardCharsets.UTF_8));

        ProjectIndexer indexer = new ProjectIndexer();
        indexer.indexProject(root);

        String alphaPath = a.getAbsolutePath();
        List<CompletionItem> completions =
            indexer.getCompletions("Alpha", alphaPath, 1, 1);
        assertTrue(hasLabel(completions, "Alpha"));
        List<CompletionItem> methods =
            indexer.getCompletions("alphaM", alphaPath, 1, 1);
        assertTrue(hasLabel(methods, "alphaMethod"));
        // Ignored build output must not be indexed.
        List<CompletionItem> ignoredSymbols =
            indexer.getCompletions("Ignored", alphaPath, 1, 1);
        assertFalse(hasLabel(ignoredSymbols, "Ignored"));

        // Re-indexing without changes must not throw and must keep symbols.
        indexer.indexProject(root);
        assertTrue(hasLabel(indexer.getCompletions("Alpha", alphaPath, 1, 1), "Alpha"));
        indexer.clear();
    }

    @Test
    public void repeatedIndexFileWithSameContentProducesSameCompletions() {
        ProjectIndexer indexer = new ProjectIndexer();
        OpenFile openFile = new OpenFile("/p/Widget.java",
            "package p;\npublic class Widget {\n  int size;\n}");
        indexer.indexFile(openFile);
        indexer.indexFile(openFile); // skipped by the parse cache
        assertTrue(hasLabel(indexer.getCompletions("Wid", "/p/Widget.java", 1, 1), "Widget"));
        assertTrue(hasLabel(indexer.getCompletions("siz", "/p/Widget.java", 1, 1), "size"));
    }

    private static boolean hasLabel(List<CompletionItem> items, String label) {
        for (CompletionItem item : items) {
            if (label.equals(item.getLabel())) return true;
        }
        return false;
    }
}
