package com.larv.ide.model;

public class OpenFile {
    private String filePath;
    private String fileName;
    private String content;
    private boolean modified;
    private int cursorLine;
    private int cursorColumn;

    public OpenFile(String filePath, String content) {
        this.filePath = filePath;
        this.fileName = new java.io.File(filePath).getName();
        this.content = content;
        this.modified = false;
        this.cursorLine = 0;
        this.cursorColumn = 0;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
        this.fileName = new java.io.File(filePath).getName();
    }

    public String getFileName() {
        return fileName;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isModified() {
        return modified;
    }

    public void setModified(boolean modified) {
        this.modified = modified;
    }

    public int getCursorLine() {
        return cursorLine;
    }

    public void setCursorLine(int cursorLine) {
        this.cursorLine = cursorLine;
    }

    public int getCursorColumn() {
        return cursorColumn;
    }

    public void setCursorColumn(int cursorColumn) {
        this.cursorColumn = cursorColumn;
    }
}