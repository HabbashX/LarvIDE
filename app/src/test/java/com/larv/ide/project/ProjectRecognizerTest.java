package com.larv.ide.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

public class ProjectRecognizerTest {

    private File tempProject() throws Exception {
        File root = Files.createTempDirectory("larvproj").toFile();
        write(new File(root, "Main.java"), "class Main {}");
        write(new File(root, "src/Util.java"), "class Util {}");
        write(new File(root, "web/index.html"), "<html></html>");
        write(new File(root, "web/style.css"), "body{}");
        write(new File(root, "app/main.py"), "print(1)");
        write(new File(root, ".larv/session.json"), "{}");
        return root;
    }

    private static void write(File f, String content) throws Exception {
        f.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(f)) {
            w.write(content);
        }
    }

    @Test
    public void detectsAllLanguages() throws Exception {
        ProjectRecognizer.Detection d = ProjectRecognizer.detect(tempProject(), null);
        assertTrue(d.languages.contains(ProjectRecognizer.JAVA));
        assertTrue(d.languages.contains(ProjectRecognizer.HTML));
        assertTrue(d.languages.contains(ProjectRecognizer.CSS));
        assertTrue(d.languages.contains(ProjectRecognizer.PYTHON));
    }

    @Test
    public void primaryIsMajorityLanguage() throws Exception {
        ProjectRecognizer.Detection d = ProjectRecognizer.detect(tempProject(), null);
        assertEquals(ProjectRecognizer.JAVA, d.primaryLanguage);
    }

    @Test
    public void activeFileWinsPrimary() throws Exception {
        File root = tempProject();
        ProjectRecognizer.Detection d = ProjectRecognizer.detect(
            root, new File(root, "web/index.html").getAbsolutePath());
        assertEquals(ProjectRecognizer.HTML, d.primaryLanguage);
    }

    @Test
    public void entryHintPrefersJavaMain() throws Exception {
        ProjectRecognizer.Detection d = ProjectRecognizer.detect(tempProject(), null);
        assertTrue(String.valueOf(d.entryFile), d.entryFile.endsWith("Main.java"));
    }

    @Test
    public void mapsExtensions() {
        assertEquals(ProjectRecognizer.JAVASCRIPT, ProjectRecognizer.languageForExtension("app.js"));
        assertEquals(ProjectRecognizer.PYTHON, ProjectRecognizer.languageForExtension("main.py"));
        assertEquals(ProjectRecognizer.HTML, ProjectRecognizer.languageForExtension("index.htm"));
        assertNull(ProjectRecognizer.languageForExtension("notes.txt"));
    }
}
