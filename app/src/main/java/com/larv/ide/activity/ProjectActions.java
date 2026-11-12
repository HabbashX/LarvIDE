package com.larv.ide.activity;

import com.larv.ide.R;

import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.larv.ide.model.FileNode;
import com.larv.ide.model.Project;
import com.larv.ide.project.ProjectManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * File- and project-level actions: create/rename/delete, the file context
 * menu, project switching and project-wide search. Extracted from
 * MainActivity; the activity remains the state owner.
 */
final class ProjectActions {

    private final MainActivity activity;
    private ActivityResultLauncher<Intent> projectCreatorLauncher;

    /** Entry file to auto-open once the created project finishes opening. */
    String pendingEntryName = null;

    ProjectActions(MainActivity activity) {
        this.activity = activity;
    }

    void registerProjectCreator() {
        projectCreatorLauncher = activity.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK
                    && result.getData() != null) {
                    String path = result.getData().getStringExtra(
                        com.larv.ide.ui.setup.ProjectCreatorActivity.EXTRA_PROJECT_PATH);
                    String entry = result.getData().getStringExtra(
                        com.larv.ide.ui.setup.ProjectCreatorActivity.EXTRA_ENTRY_NAME);
                    if (path != null && !path.isEmpty()) {
                        File dir = new File(path);
                        pendingEntryName = (entry != null && !entry.isEmpty()) ? entry : null;
                        activity.switchToProject(new Project(dir.getName(), dir.getAbsolutePath()));
                    }
                }
            });
    }

    void startProjectCreator() {
        projectCreatorLauncher.launch(new Intent(activity,
            com.larv.ide.ui.setup.ProjectCreatorActivity.class));
    }

    private String resolveTargetDirectory() {
        String selected = activity.selectedDirectory;
        if (selected != null && !selected.isEmpty()) {
            File dir = new File(selected);
            if (dir.exists() && dir.isDirectory()) {
                return dir.getAbsolutePath();
            }
        }
        return activity.currentProject != null ? activity.currentProject.getPath() : "";
    }

    void showNewFileDialog() {
        String target = resolveTargetDirectory();
        if (target.isEmpty()) return;

        final String parentPath = target;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("New File");
        builder.setMessage("In: " + new File(parentPath).getName());

        EditText input = MainDialogs.createIdeInput(activity,
            "File name with extension (e.g., Main.java)");
        builder.setView(MainDialogs.wrapDialogView(activity, input));

        builder.setPositiveButton("Create", (dialog, which) -> {
            String fileName = input.getText().toString().trim();
            if (fileName.isEmpty()) {
                Toast.makeText(activity, "File name cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            if (ProjectManager.isCppFileName(fileName) && !ProjectManager.isCppEnabled()) {
                dialog.dismiss();
                activity.showEmbeddedSetupDialog();
                return;
            }
            dialog.dismiss();
            activity.projectManager.createFile(parentPath, fileName,
                new ProjectManager.OnFileOperationCallback() {
                    @Override
                    public void onSuccess(File file) {
                        activity.openFileInEditor(file);
                        activity.projectManager.refreshFileTree();
                    }
                    @Override
                    public void onError(String error) {
                        activity.runOnUiThread(() -> Toast.makeText(activity, error,
                            Toast.LENGTH_SHORT).show());
                    }
                });
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    void showNewFolderDialog() {
        String target = resolveTargetDirectory();
        if (target.isEmpty()) return;

        final String parentPath = target;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("New Folder");
        builder.setMessage("In: " + new File(parentPath).getName());

        EditText input = MainDialogs.createIdeInput(activity, "Folder name");
        builder.setView(MainDialogs.wrapDialogView(activity, input));

        builder.setPositiveButton("Create", (dialog, which) -> {
            String folderName = input.getText().toString().trim();
            if (folderName.isEmpty()) {
                Toast.makeText(activity, "Folder name cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            activity.projectManager.createFolder(parentPath, folderName,
                new ProjectManager.OnFileOperationCallback() {
                    @Override
                    public void onSuccess(File file) {
                        activity.selectedDirectory = file.getAbsolutePath();
                        activity.runOnUiThread(() -> {
                            Toast.makeText(activity, "Folder created", Toast.LENGTH_SHORT).show();
                            activity.statusText.setText("Folder created: " + file.getName());
                            activity.projectManager.refreshFileTree();
                        });
                    }
                    @Override
                    public void onError(String error) {
                        activity.runOnUiThread(() -> Toast.makeText(activity, error,
                            Toast.LENGTH_SHORT).show());
                    }
                });
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    void showFileContextMenu(FileNode node, View anchor) {
        PopupMenu popup = new PopupMenu(activity, anchor);
        popup.getMenu().add(node.getName()).setEnabled(false);

        if (node.getType() == FileNode.Type.DIRECTORY) {
            popup.getMenu().add("New File").setOnMenuItemClickListener(item -> {
                activity.selectedDirectory = node.getPath();
                showNewFileDialog();
                return true;
            });
            popup.getMenu().add("New Folder").setOnMenuItemClickListener(item -> {
                activity.selectedDirectory = node.getPath();
                showNewFolderDialog();
                return true;
            });
            popup.getMenu().add("Open").setOnMenuItemClickListener(item -> {
                activity.selectedDirectory = node.getPath();
                activity.fileTreeAdapter.toggleExpansion(node);
                return true;
            });
        } else {
            popup.getMenu().add("Open").setOnMenuItemClickListener(item -> {
                activity.openFileInEditor(new File(node.getPath()));
                return true;
            });
        }

        popup.getMenu().add("Rename").setOnMenuItemClickListener(item -> {
            showRenameDialog(node);
            return true;
        });
        popup.getMenu().add("Delete").setOnMenuItemClickListener(item -> {
            confirmDelete(node);
            return true;
        });

        popup.show();
    }

    private void showRenameDialog(FileNode node) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Rename");

        EditText input = MainDialogs.createIdeInput(activity, null);
        input.setText(node.getName());
        input.setSelection(input.getText().length());
        builder.setView(MainDialogs.wrapDialogView(activity, input));

        builder.setPositiveButton("Rename", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty() && !newName.equals(node.getName())) {
                activity.projectManager.renameFile(new File(node.getPath()), newName,
                    new ProjectManager.OnFileOperationCallback() {
                        @Override
                        public void onSuccess(File file) {
                            activity.projectManager.refreshFileTree();
                        }
                        @Override
                        public void onError(String error) {
                            activity.runOnUiThread(() -> Toast.makeText(activity, error,
                                Toast.LENGTH_SHORT).show());
                        }
                    });
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void confirmDelete(FileNode node) {
        new AlertDialog.Builder(activity)
            .setTitle("Delete " + node.getName() + "?")
            .setMessage("This action cannot be undone.")
            .setPositiveButton("Delete", (dialog, which) -> {
                activity.projectManager.deleteFile(new File(node.getPath()),
                    new ProjectManager.OnFileOperationCallback() {
                        @Override
                        public void onSuccess(File file) {
                            activity.projectManager.refreshFileTree();
                            if (file.isDirectory()
                                && activity.selectedDirectory.startsWith(file.getAbsolutePath())) {
                                activity.selectedDirectory = activity.currentProject != null
                                    ? activity.currentProject.getPath() : "";
                            }
                        }
                        @Override
                        public void onError(String error) {
                            activity.runOnUiThread(() -> Toast.makeText(activity, error,
                                Toast.LENGTH_SHORT).show());
                        }
                    });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    void showOpenProjectDialog() {
        List<Project> projects = activity.projectManager.getProjects();
        if (projects.isEmpty()) {
            Toast.makeText(activity, "No projects found. Create one first.",
                Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = new String[projects.size()];
        for (int i = 0; i < projects.size(); i++) {
            names[i] = projects.get(i).getName();
        }

        new AlertDialog.Builder(activity)
            .setTitle("Open Project")
            .setItems(names, (dialog, which) -> activity.switchToProject(projects.get(which)))
            .show();
    }

    void showProjectSearchDialog() {
        if (activity.currentProject == null) {
            Toast.makeText(activity, "Open a project first", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = MainDialogs.createIdeInput(activity, "Search text");
        new AlertDialog.Builder(activity)
            .setTitle("Search in Project")
            .setView(MainDialogs.wrapDialogView(activity, input))
            .setPositiveButton("Search", (dialog, which) -> {
                String query = input.getText().toString();
                if (query.trim().isEmpty()) return;
                activity.compilerExecutor.execute(() -> {
                    List<String[]> found = searchProjectFiles(query);
                    activity.runOnUiThread(() -> showSearchResults(query, found));
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private List<String[]> searchProjectFiles(String query) {
        List<String[]> out = new ArrayList<>();
        File root = activity.currentProject.getRootDir();
        List<File> files = new ArrayList<>();
        collectProjectFiles(root, files, 0);
        String q = query.toLowerCase(java.util.Locale.ROOT);
        for (File f : files) {
            if (out.size() >= 300) break;
            if (f.length() > 2 * 1024 * 1024) continue;
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f),
                        java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                int no = 0;
                while ((line = br.readLine()) != null) {
                    no++;
                    if (line.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                        out.add(new String[]{f.getAbsolutePath(),
                            String.valueOf(no), line.trim()});
                        if (out.size() >= 300) break;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private void collectProjectFiles(File dir, List<File> out, int depth) {
        if (depth > 8 || dir == null) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.getName().startsWith(".")) continue;
            if (c.isDirectory()) collectProjectFiles(c, out, depth + 1);
            else out.add(c);
        }
    }

    private void showSearchResults(String query, List<String[]> results) {
        if (results.isEmpty()) {
            Toast.makeText(activity, "No matches for \"" + query + "\"",
                Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[results.size()];
        for (int i = 0; i < results.size(); i++) {
            String[] r = results.get(i);
            labels[i] = new File(r[0]).getName() + ":" + r[1] + "  " + r[2];
        }
        new AlertDialog.Builder(activity)
            .setTitle("Search results (" + results.size() + ")")
            .setItems(labels, (dialog, which) -> {
                String[] r = results.get(which);
                activity.openFileInEditor(new File(r[0]));
            })
            .show();
    }
}
