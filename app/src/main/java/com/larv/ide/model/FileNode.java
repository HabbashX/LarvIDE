package com.larv.ide.model;

import android.os.Build;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class FileNode implements Serializable {

    public enum Type {
        FILE, DIRECTORY
    }

    private String name;
    private String path;
    private Type type;
    private List<FileNode> children;
    private boolean expanded;
    private int depth;

    public FileNode() {
        this.children = new ArrayList<>();
        this.expanded = true;
    }

    public FileNode(String name, String path, Type type) {
        this();
        this.name = name;
        this.path = path;
        this.type = type;
    }

    public static FileNode fromFile(File file, int depth) {
        FileNode node = new FileNode(file.getName(), file.getAbsolutePath(),
            file.isDirectory() ? Type.DIRECTORY : Type.FILE);
        node.depth = depth;
        return node;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public List<FileNode> getChildren() {
        return children;
    }

    public void setChildren(List<FileNode> children) {
        this.children = children;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public boolean isJavaFile() {
        return type == Type.FILE && name.endsWith(".java");
    }

    public void addChild(FileNode child) {
        children.add(child);
    }

    public void sortChildren() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            children.sort((a, b) -> {
                if (a.type != b.type) {
                    return a.type == Type.DIRECTORY ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            });
        }
    }
}
