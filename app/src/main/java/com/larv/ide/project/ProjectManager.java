package com.larv.ide.project;

import android.content.Context;
import android.util.Log;

import com.larv.ide.model.FileNode;
import com.larv.ide.model.Project;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProjectManager {
    private static final String TAG = "ProjectManager";
    private static final String PROJECTS_DIR_NAME = "JavaProjects";

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final File projectsRootDir;
    private Project currentProject;
    private OnProjectChangeListener listener;

    public interface OnProjectChangeListener {
        void onProjectOpened(Project project);
        void onProjectClosed();
        void onFileTreeUpdated(List<FileNode> rootNodes);
        void onError(String message);
    }

    public ProjectManager(Context context) {
        this.context = context;
        this.projectsRootDir = new File(context.getExternalFilesDir(null), PROJECTS_DIR_NAME);
        this.projectsRootDir.mkdirs();
    }

    public void setListener(OnProjectChangeListener listener) {
        this.listener = listener;
    }

    public File getProjectsRootDir() {
        return projectsRootDir;
    }

    public List<Project> getProjects() {
        List<Project> projects = new ArrayList<>();
        File[] dirs = projectsRootDir.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                projects.add(new Project(dir.getName(), dir.getAbsolutePath()));
            }
        }
        return projects;
    }

    public void createProject(String name, OnProjectCreatedCallback callback) {
        executor.execute(() -> {
            File projectDir = new File(projectsRootDir, sanitizeFileName(name));
            if (projectDir.exists()) {
                int i = 1;
                while (true) {
                    File newDir = new File(projectsRootDir, sanitizeFileName(name) + "_" + i);
                    if (!newDir.exists()) {
                        projectDir = newDir;
                        break;
                    }
                    i++;
                }
            }
            
            boolean success = projectDir.mkdirs();
            if (success) {
                File mainFile = new File(projectDir, "Main.java");
                String template = context.getString(com.larv.ide.R.string.java_file_template);
                try (FileOutputStream fos = new FileOutputStream(mainFile)) {
                    fos.write(String.format(template, "Main").getBytes("UTF-8"));
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create Main.java", e);
                }
                
                Project project = new Project(projectDir.getName(), projectDir.getAbsolutePath());
                if (callback != null) {
                    callback.onCreated(project);
                }
            } else {
                if (callback != null) {
                    callback.onError("Failed to create project directory");
                }
            }
        });
    }

    public void openProject(Project project) {
        executor.execute(() -> {
            currentProject = project;
            List<FileNode> rootNodes = buildFileTree(project.getRootDir());
            if (listener != null) {
                listener.onProjectOpened(project);
                listener.onFileTreeUpdated(rootNodes);
            }
        });
    }

    public void closeProject() {
        currentProject = null;
        if (listener != null) {
            listener.onProjectClosed();
        }
    }

    public Project getCurrentProject() {
        return currentProject;
    }

    public void createFile(String parentPath, String fileName, OnFileOperationCallback callback) {
        executor.execute(() -> {
            File parentDir = new File(parentPath);
            if (!parentDir.exists() || !parentDir.isDirectory()) {
                if (callback != null) callback.onError("Invalid parent directory");
                return;
            }

            final String finalFileName = fileName.endsWith(".java") ? fileName : fileName + ".java";
            File newFile = new File(parentDir, finalFileName);
            if (newFile.exists()) {
                if (callback != null) callback.onError("File already exists");
                return;
            }

            String template = context.getString(com.larv.ide.R.string.java_file_template);
            String className = finalFileName.replace(".java", "");
            String content = String.format(template, className);

            try (FileOutputStream fos = new FileOutputStream(newFile)) {
                fos.write(content.getBytes("UTF-8"));
                if (callback != null) callback.onSuccess(newFile);
            } catch (IOException e) {
                Log.e(TAG, "Failed to create file", e);
                if (callback != null) callback.onError("Failed to create file: " + e.getMessage());
            }
        });
    }

    public void createFolder(String parentPath, String folderName, OnFileOperationCallback callback) {
        executor.execute(() -> {
            File parentDir = new File(parentPath);
            if (!parentDir.exists() || !parentDir.isDirectory()) {
                if (callback != null) callback.onError("Invalid parent directory");
                return;
            }

            File newFolder = new File(parentDir, folderName);
            if (newFolder.exists()) {
                if (callback != null) callback.onError("Folder already exists");
                return;
            }

            if (newFolder.mkdirs()) {
                if (callback != null) callback.onSuccess(newFolder);
            } else {
                if (callback != null) callback.onError("Failed to create folder");
            }
        });
    }

    public void deleteFile(File file, OnFileOperationCallback callback) {
        executor.execute(() -> {
            try {
                if (file.isDirectory()) {
                    deleteRecursive(file);
                } else {
                    boolean deleted = file.delete();
                    if (!deleted) throw new IOException("Delete failed");
                }
                if (callback != null) callback.onSuccess(file);
            } catch (IOException e) {
                Log.e(TAG, "Failed to delete file", e);
                if (callback != null) callback.onError("Failed to delete: " + e.getMessage());
            }
        });
    }

    private void deleteRecursive(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        if (!file.delete()) {
            throw new IOException("Failed to delete: " + file.getAbsolutePath());
        }
    }

    public void renameFile(File file, String newName, OnFileOperationCallback callback) {
        executor.execute(() -> {
            final String finalName = (!newName.endsWith(".java") && file.isFile()) ? newName + ".java" : newName;
            
            File newFile = new File(file.getParent(), finalName);
            if (newFile.exists()) {
                if (callback != null) callback.onError("File already exists");
                return;
            }

            boolean renamed = file.renameTo(newFile);
            if (renamed) {
                if (callback != null) callback.onSuccess(newFile);
            } else {
                if (callback != null) callback.onError("Failed to rename");
            }
        });
    }

    public void readFile(File file, OnFileReadCallback callback) {
        executor.execute(() -> {
            try {
                byte[] bytes = new byte[(int) file.length()];
                try (FileInputStream fis = new FileInputStream(file)) {
                    fis.read(bytes);
                }
                String content = new String(bytes, "UTF-8");
                if (callback != null) callback.onContent(content);
            } catch (IOException e) {
                Log.e(TAG, "Failed to read file", e);
                if (callback != null) callback.onError("Failed to read: " + e.getMessage());
            }
        });
    }

    public void writeFile(File file, String content, OnFileOperationCallback callback) {
        executor.execute(() -> {
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content.getBytes("UTF-8"));
                if (callback != null) callback.onSuccess(file);
            } catch (IOException e) {
                Log.e(TAG, "Failed to write file", e);
                if (callback != null) callback.onError("Failed to write: " + e.getMessage());
            }
        });
    }

    public void refreshFileTree() {
        if (currentProject != null) {
            executor.execute(() -> {
                List<FileNode> rootNodes = buildFileTree(currentProject.getRootDir());
                if (listener != null) {
                    listener.onFileTreeUpdated(rootNodes);
                }
            });
        }
    }

    private List<FileNode> buildFileTree(File root) {
        List<FileNode> nodes = new ArrayList<>();
        File[] files = root.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.getName().startsWith(".")) {
                    nodes.add(buildNode(f, 0));
                }
            }
        }
        Collections.sort(nodes, (a, b) -> {
            if (a.getType() != b.getType()) {
                return a.getType() == FileNode.Type.DIRECTORY ? -1 : 1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return nodes;
    }

    private FileNode buildNode(File file, int depth) {
        FileNode node = FileNode.fromFile(file, depth);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!child.getName().startsWith(".")) {
                        node.addChild(buildNode(child, depth + 1));
                    }
                }
                node.sortChildren();
            }
        }
        return node;
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    public interface OnProjectCreatedCallback {
        void onCreated(Project project);
        void onError(String error);
    }

    public interface OnFileOperationCallback {
        void onSuccess(File file);
        void onError(String error);
    }

    public interface OnFileReadCallback {
        void onContent(String content);
        void onError(String error);
    }
}