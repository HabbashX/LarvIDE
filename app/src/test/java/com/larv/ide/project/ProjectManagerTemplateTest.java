package com.larv.ide.project;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProjectManagerTemplateTest {

    @Test
    public void pythonTemplateHasEntrypoint() {
        String t = ProjectManager.templateFor("main.py");
        assertTrue(t.contains("def main():"));
        assertTrue(t.contains("__main__"));
    }

    @Test
    public void javaTemplateHasMainMethod() {
        String t = ProjectManager.templateFor("Main.java");
        assertTrue(t.contains("public class Main"));
        assertTrue(t.contains("public static void main"));
    }

    @Test
    public void htmlTemplateIsValidSkeleton() {
        String t = ProjectManager.templateFor("page.html");
        assertTrue(t.contains("<!DOCTYPE html>"));
        assertTrue(t.contains("<title>page</title>"));
    }

    @Test
    public void cssAndJsTemplatesNonEmpty() {
        assertTrue(ProjectManager.templateFor("style.css").contains("body {"));
        assertTrue(ProjectManager.templateFor("app.js").contains("console.log"));
    }

    @Test
    public void unknownExtensionGivesEmptyTemplate() {
        assertTrue(ProjectManager.templateFor("notes.txt").isEmpty());
    }
}
