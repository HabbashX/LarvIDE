package com.larv.ide.model;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Project implements Serializable {
    private String name;
    private String path;
    private long lastModified;
    private List<String> openFiles;

    public Project() {
        this.openFiles = new ArrayList<>();
    }

    public Project(String name, String path) {
        this.name = name;
        this.path = path;
        this.lastModified = System.currentTimeMillis();
        this.openFiles = new ArrayList<>();
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

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public List<String> getOpenFiles() {
        return openFiles;
    }

    public void setOpenFiles(List<String> openFiles) {
        this.openFiles = openFiles;
    }

    public File getRootDir() {
        return new File(path);
    }

    public File getFile(String fileName) {
        return new File(path, fileName);
    }

    @Override
    public String toString() {
        return name;
    }
}