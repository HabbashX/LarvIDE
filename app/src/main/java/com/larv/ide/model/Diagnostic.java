package com.larv.ide.model;

public class Diagnostic {
    public enum Severity {
        ERROR, WARNING, INFO
    }

    private String filePath;
    private int line;
    private int column;
    private int endLine;
    private int endColumn;
    private String message;
    private Severity severity;
    private String code;

    public Diagnostic() {}

    public Diagnostic(String filePath, int line, int column, String message, Severity severity) {
        this.filePath = filePath;
        this.line = line;
        this.column = column;
        this.message = message;
        this.severity = severity;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public int getColumn() {
        return column;
    }

    public void setColumn(int column) {
        this.column = column;
    }

    public int getEndLine() {
        return endLine;
    }

    public void setEndLine(int endLine) {
        this.endLine = endLine;
    }

    public int getEndColumn() {
        return endColumn;
    }

    public void setEndColumn(int endColumn) {
        this.endColumn = endColumn;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getShortLocation() {
        return new java.io.File(filePath).getName() + ":" + line + ":" + column;
    }

    @Override
    public String toString() {
        return severity + " at " + getShortLocation() + ": " + message;
    }
}