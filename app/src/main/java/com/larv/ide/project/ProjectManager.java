package com.larv.ide.project;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.larv.ide.model.FileNode;
import com.larv.ide.model.Project;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
                    fos.write(String.format(template, "Main").getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create Main.java", e);
                }

                File buildFile = new File(projectDir, "larvbuild.json");
                String buildTemplate = "{\n"
                    + "  \"language\": \"Java\",\n"
                    + "  \"main\": \"Main\",\n"
                    + "  \"entry\": \"Main.java\",\n"
                    + "  \"dependencies\": [\n"
                    + "  ],\n"
                    + "  \"repositories\": [\n"
                    + "    \"https://repo1.maven.org/maven2\"\n"
                    + "  ]\n"
                    + "}\n";
                try (FileOutputStream fos = new FileOutputStream(buildFile)) {
                    fos.write(buildTemplate.getBytes("UTF-8"));
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create larvbuild.json", e);
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

            final String finalFileName = hasKnownExtension(fileName) ? fileName : fileName + ".java";
            File newFile = new File(parentDir, finalFileName);
            if (newFile.exists()) {
                if (callback != null) callback.onError("File already exists");
                return;
            }

            String content = templateFor(finalFileName);

            try (FileOutputStream fos = new FileOutputStream(newFile)) {
                fos.write(content.getBytes("UTF-8"));
                if (callback != null) callback.onSuccess(newFile);
            } catch (IOException e) {
                Log.e(TAG, "Failed to create file", e);
                if (callback != null) callback.onError("Failed to create file: " + e.getMessage());
            }
        });
    }

    private static boolean hasKnownExtension(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".java") || lower.endsWith(".py") || lower.endsWith(".js")
            || lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".css")
            || lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".md")
            || lower.endsWith(".txt");
    }

    public static String templateFor(String fileName) {
        String lower = fileName.toLowerCase();
        String base = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        if (lower.endsWith(".py")) {
            return "def main():\n    print(\"Hello from LarvIDE!\")\n\n\nif __name__ == \"__main__\":\n    main()\n";
        }
        if (lower.endsWith(".js")) {
            return "function main() {\n    console.log(\"Hello from LarvIDE!\");\n}\n\nmain();\n";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "<!DOCTYPE html>\n<html>\n<head>\n    <meta charset=\"utf-8\">\n"
                + "    <title>" + base + "</title>\n</head>\n<body>\n    <h1>Hello from LarvIDE!</h1>\n"
                + "    <p>Edit this page and hit Run.</p>\n</body>\n</html>\n";
        }
        if (lower.endsWith(".css")) {
            return "body {\n    font-family: sans-serif;\n    background: #191a1c;\n    color: #bcbec4;\n}\n";
        }
        if (lower.endsWith(".json")) {
            return "{\n    \n}\n";
        }
        if (lower.endsWith(".java")) {
            return "public class " + base + " {\n    public static void main(String[] args) {\n"
                + "        System.out.println(\"Hello from LarvIDE!\");\n    }\n}\n";
        }
        return "";
    }

    public void createFolder(String parentPath, String folderName, OnFileOperationCallback callback) {
        executor.execute(() -> {
            try {
                if (folderName == null || folderName.trim().isEmpty()) {
                    if (callback != null) callback.onError("Folder name cannot be empty");
                    return;
                }

                File parentDir = new File(parentPath);
                if (!parentDir.exists() || !parentDir.isDirectory()) {
                    if (callback != null) callback.onError("Invalid parent directory");
                    return;
                }

                File newFolder = new File(parentDir, folderName.trim());
                if (newFolder.exists()) {
                    if (callback != null) callback.onError("Folder already exists");
                    return;
                }

                if (newFolder.mkdirs()) {
                    if (callback != null) callback.onSuccess(newFolder);
                } else {
                    if (callback != null) callback.onError("Failed to create folder");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to create folder", e);
                if (callback != null) callback.onError("Failed to create folder: " + e.getMessage());
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

    public void moveFile(File source, File targetDir, OnFileOperationCallback callback) {
        executor.execute(() -> {
            try {
                if (source == null || !source.exists()) {
                    if (callback != null) callback.onError("Source not found");
                    return;
                }
                if (targetDir == null || !targetDir.exists() || !targetDir.isDirectory()) {
                    if (callback != null) callback.onError("Invalid target folder");
                    return;
                }
                if (source.getParentFile() != null
                    && source.getParentFile().getAbsolutePath().equals(targetDir.getAbsolutePath())) {
                    if (callback != null) callback.onSuccess(source);
                    return;
                }
                if (source.isDirectory() && targetDir.getAbsolutePath().startsWith(source.getAbsolutePath())) {
                    if (callback != null) callback.onError("Cannot move a folder into itself");
                    return;
                }
                File dest = new File(targetDir, source.getName());
                if (dest.exists()) {
                    if (callback != null) callback.onError("A file or folder with that name already exists in the target");
                    return;
                }
                if (source.renameTo(dest)) {
                    if (callback != null) callback.onSuccess(dest);
                } else {
                    if (callback != null) callback.onError("Failed to move");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to move file", e);
                if (callback != null) callback.onError("Failed to move: " + e.getMessage());
            }
        });
    }

    public void readFile(File file, OnFileReadCallback callback) {
        executor.execute(() -> {
            try {
                long length = file.length();
                if (length > Integer.MAX_VALUE) {
                    throw new IOException("File too large to read");
                }
                byte[] bytes = new byte[(int) length];
                try (FileInputStream fis = new FileInputStream(file)) {
                    int total = 0;
                    while (total < bytes.length) {
                        int read = fis.read(bytes, total, bytes.length - total);
                        if (read == -1) break;
                        total += read;
                    }
                }
                String content = new String(bytes, 0, bytes.length, "UTF-8");
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

    @NonNull
    private List<FileNode> buildFileTree(@NonNull File root) {
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

    @NonNull
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
