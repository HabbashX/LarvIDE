package com.larv.ide.completion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.larv.ide.model.OpenFile;

import java.util.List;

public class ProjectIndexerTest {

    private static boolean has(List<CompletionItem> items, String label) {
        for (CompletionItem i : items) {
            if (label.equals(i.getLabel())) return true;
        }
        return false;
    }

    @Test
    public void completesClassesAndMethodsByPrefix() {
        ProjectIndexer indexer = new ProjectIndexer();
        indexer.indexFile(new OpenFile("/t/Foo.java",
            "package com.t;\npublic class Foo {\n    int count;\n"
                + "    void barMethod() { }\n}\n"));

        assertTrue(has(indexer.getCompletions("Fo", "/t/Foo.java", 1, 1), "Foo"));
        assertTrue(has(indexer.getCompletions("barMeth", "/t/Foo.java", 1, 1), "barMethod"));
        assertTrue(indexer.getCompletions("zzzz", "/t/Foo.java", 1, 1).isEmpty());
    }

    @Test
    public void memberCompletionResolvesLocalVariable() {
        ProjectIndexer indexer = new ProjectIndexer();
        List<CompletionItem> members = indexer.getCompletions("", "/t/T.java", 4, 4,
            "sb",
            "class T {\n"
                + "    void m() {\n"
                + "        StringBuilder sb = new StringBuilder();\n"
                + "        sb.\n"
                + "    }\n"
                + "}\n");
        assertTrue(has(members, "append"));
        assertTrue(has(members, "reverse"));
        assertTrue(has(members, "toString"));
    }

    @Test
    public void memberCompletionResolvesSystemOut() {
        ProjectIndexer indexer = new ProjectIndexer();
        List<CompletionItem> members = indexer.getCompletions("", "/t/T.java", 2, 16,
            "System.out", "class T {\n    System.out.p\n}");
        assertTrue(has(members, "println"));
        assertTrue(has(members, "print"));
    }

    @Test
    public void memberCompletionMapsPrimitives() {
        ProjectIndexer indexer = new ProjectIndexer();
        List<CompletionItem> intMembers = indexer.getCompletions("", "/t/T.java", 3, 3,
            "x",
            "class T {\n"
                + "    void m() {\n"
                + "        int x = 5;\n"
                + "        x.\n"
                + "    }\n"
                + "}\n");
        assertTrue(has(intMembers, "parseInt"));

        List<CompletionItem> strMembers = indexer.getCompletions("", "/t/T.java", 3, 5,
            "name",
            "class T {\n"
                + "    void m(String name) {\n"
                + "        name.\n"
                + "    }\n"
                + "}\n");
        assertTrue(has(strMembers, "substring"));
    }

    @Test
    public void memberCompletionStaticAccess() {
        ProjectIndexer indexer = new ProjectIndexer();
        List<CompletionItem> math = indexer.getCompletions("", "/t/T.java", 1, 24,
            "Math", "class T { double d = Math. }");
        assertTrue(has(math, "pow"));
        assertTrue(has(math, "PI"));
    }

    @Test
    public void importCandidatesCoverStdlibAndProject() {
        ProjectIndexer indexer = new ProjectIndexer();
        String stdlib = indexer.findImportCandidates("ArrayList");
        assertTrue(stdlib.contains("java.util.ArrayList"));

        indexer.indexFile(new OpenFile("/t/Widget.java",
            "package com.me;\npublic class Widget {}\n"));
        String project = indexer.findImportCandidates("Widget");
        assertTrue(project.contains("com.me.Widget"));
    }

    @Test
    public void unknownReceiverYieldsNoMembers() {
        ProjectIndexer indexer = new ProjectIndexer();
        List<CompletionItem> none = indexer.getCompletions("", "/t/T.java", 1, 5,
            "mysteryVar", "class T { mysteryVar. }");
        assertFalse(has(none, "append"));
    }
}
