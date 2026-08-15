package com.larv.ide.completion;

public class CompletionItem {
    public enum Kind {
        CLASS, INTERFACE, ENUM, RECORD, ANNOTATION,
        METHOD, FIELD, PARAMETER, LOCAL_VARIABLE,
        PACKAGE, KEYWORD, SNIPPET
    }

    private String label;
    private String detail;
    private String documentation;
    private Kind kind;
    private String insertText;
    private String filterText;
    private int sortPriority;

    public CompletionItem() {}

    public CompletionItem(String label, Kind kind) {
        this.label = label;
        this.kind = kind;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getDocumentation() {
        return documentation;
    }

    public void setDocumentation(String documentation) {
        this.documentation = documentation;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public String getInsertText() {
        return insertText;
    }

    public void setInsertText(String insertText) {
        this.insertText = insertText;
    }

    public String getFilterText() {
        return filterText;
    }

    public void setFilterText(String filterText) {
        this.filterText = filterText;
    }

    public int getSortPriority() {
        return sortPriority;
    }

    public void setSortPriority(int sortPriority) {
        this.sortPriority = sortPriority;
    }

    public int getKindOrder() {
        switch (kind) {
            case CLASS: case INTERFACE: case ENUM: case RECORD: return 1;
            case METHOD: return 2;
            case FIELD: return 3;
            case PACKAGE: return 4;
            case KEYWORD: return 5;
            case SNIPPET: return 6;
            default: return 10;
        }
    }
}