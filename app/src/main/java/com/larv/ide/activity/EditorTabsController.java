package com.larv.ide.activity;

import com.larv.ide.R;

import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.tabs.TabLayout;
import com.larv.ide.model.OpenFile;
import com.larv.ide.project.ProjectManager;

import java.io.File;
import java.io.IOException;

/**
 * Owns the editor tab lifecycle: opening files, switching/closing tabs,
 * modified markers, saving (sync, async and autosave) and window titles.
 * Extracted from MainActivity; tab state itself still lives on the activity
 * because the session manager reads it directly.
 */
final class EditorTabsController {

    private final MainActivity activity;

    EditorTabsController(MainActivity activity) {
        this.activity = activity;
    }

    void openFileInEditor(@NonNull File file) {
        String filePath = file.getAbsolutePath();

        OpenFile existing = activity.findOpenFile(filePath);
        if (existing != null) {
            switchToTab(activity.openFiles.indexOf(existing));
            return;
        }

        activity.projectManager.readFile(file, new ProjectManager.OnFileReadCallback() {
            @Override
            public void onContent(String content) {
                activity.runOnUiThread(() -> {
                    OpenFile openFile = new OpenFile(filePath, content);
                    activity.openFiles.add(openFile);
                    activity.openFilesByPath.put(filePath, openFile);

                    int index = activity.openFiles.size() - 1;
                    TabLayout.Tab tab = activity.tabLayout.newTab().setText(file.getName());
                    activity.tabLayout.addTab(tab, index, true);

                    ensureEditorFragment();
                    activity.tabLayout.selectTab(tab);
                    activity.noEditorPlaceholder.setVisibility(View.GONE);
                    activity.welcomeView.setVisibility(View.GONE);
                    activity.indexerExecutor.execute(
                        () -> activity.projectIndexer.indexFile(openFile));
                    activity.statusText.setText(file.getName() + " - Loading");
                    activity.runController.updateRunActionState(filePath, content);
                });
            }

            @Override
            public void onError(String error) {
                activity.runOnUiThread(() ->
                    Toast.makeText(activity, error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    void ensureEditorFragment() {
        if (activity.editorFragment == null) {
            activity.editorFragment = new com.larv.ide.ui.fragment.EditorFragment();
            activity.editorFragment.setListener(activity);
        }
        if (activity.editorFragment.isAdded()) return;
        activity.getSupportFragmentManager().beginTransaction()
            .replace(R.id.editorContainer, activity.editorFragment, "editor")
            .commit();
    }

    void switchToTab(int index) {
        if (index < 0 || index >= activity.openFiles.size()) return;

        String filePath = activity.openFiles.get(index).getFilePath();
        activity.currentEditorFile = filePath;

        ensureEditorFragment();
        OpenFile openFile = activity.findOpenFile(filePath);
        if (openFile != null) {
            activity.editorFragment.setContent(filePath, openFile.getContent());
            activity.runController.updateRunActionState(filePath, openFile.getContent());
        }
        // Re-assert prefs on every switch: guarantees font/theme survive
        // fragment recreation, project switches, and restores.
        activity.applyPrefsToEditor();
        if (activity.openFiles.isEmpty()) {
            activity.noEditorPlaceholder.setVisibility(View.VISIBLE);
        }
        activity.sessionManager.saveNow();
    }

    void closeTab(int index) {
        if (index < 0 || index >= activity.openFiles.size()) return;

        OpenFile openFile = activity.openFiles.get(index);
        String filePath = openFile.getFilePath();

        activity.projectIndexer.removeFile(openFile.getFileName());

        activity.openFiles.remove(index);
        activity.openFilesByPath.remove(filePath);
        activity.tabLayout.removeTabAt(index);

        if (!activity.openFiles.isEmpty()) {
            int newIndex = Math.min(index, activity.openFiles.size() - 1);
            activity.tabLayout.selectTab(activity.tabLayout.getTabAt(newIndex));
        } else {
            activity.currentEditorFile = "";
            if (activity.editorFragment != null) {
                activity.editorFragment.setContent("", "");
            }
            activity.noEditorPlaceholder.setVisibility(View.VISIBLE);
            activity.runController.setRunEnabled(false);
            activity.statusText.setText("No runnable file");
        }
        activity.sessionManager.saveNow();
    }

    void updateTabModified(String filePath, boolean modified) {
        int index = indexOfOpenFile(filePath);
        if (index >= 0) {
            TabLayout.Tab tab = activity.tabLayout.getTabAt(index);
            if (tab != null) {
                String name = activity.openFiles.get(index).getFileName();
                tab.setText(modified ? "* " + name : name);
            }
        }
    }

    int indexOfOpenFile(String filePath) {
        OpenFile openFile = filePath != null ? activity.openFilesByPath.get(filePath) : null;
        return openFile != null ? activity.openFiles.indexOf(openFile) : -1;
    }

    @Nullable
    OpenFile findOpenFile(String filePath) {
        return filePath != null ? activity.openFilesByPath.get(filePath) : null;
    }

    void showTabCloseMenu(int index, View anchor) {
        PopupMenu popup = new PopupMenu(activity, anchor);
        popup.getMenu().add("Close").setOnMenuItemClickListener(item -> {
            closeTab(index);
            return true;
        });
        popup.getMenu().add("Close Others").setOnMenuItemClickListener(item -> {
            closeOtherTabs(index);
            return true;
        });
        popup.getMenu().add("Close All").setOnMenuItemClickListener(item -> {
            closeAllTabs();
            return true;
        });
        popup.show();
    }

    void closeOtherTabs(int keepIndex) {
        for (int i = activity.openFiles.size() - 1; i >= 0; i--) {
            if (i != keepIndex) {
                closeTab(i);
            }
        }
    }

    void closeAllTabs() {
        for (int i = activity.openFiles.size() - 1; i >= 0; i--) {
            closeTab(i);
        }
    }

    void saveCurrentFile() {
        if (!activity.currentEditorFile.isEmpty()) {
            OpenFile openFile = activity.findOpenFile(activity.currentEditorFile);
            if (openFile != null && openFile.isModified()) {
                String filePath = activity.currentEditorFile;
                activity.projectManager.writeFile(new File(filePath), openFile.getContent(),
                    new ProjectManager.OnFileOperationCallback() {
                        @Override
                        public void onSuccess(File file) {
                            openFile.setModified(false);
                            updateTabModified(filePath, false);
                            activity.statusText.setText("Saved: " + file.getName());
                            // Keep symbols in sync without a re-scan.
                            activity.projectIndexer.indexFile(openFile);
                        }
                        @Override
                        public void onError(String error) {
                            activity.runOnUiThread(() -> Toast.makeText(activity, error,
                                Toast.LENGTH_SHORT).show());
                        }
                    });
            } else {
                activity.statusText.setText("Nothing to save");
            }
        }
    }

    void saveAllFiles() {
        for (OpenFile openFile : activity.openFiles) {
            if (openFile.isModified()) {
                String filePath = openFile.getFilePath();
                activity.projectManager.writeFile(new File(filePath), openFile.getContent(),
                    new ProjectManager.OnFileOperationCallback() {
                        @Override
                        public void onSuccess(File file) {
                            openFile.setModified(false);
                            activity.runOnUiThread(() -> updateTabModified(filePath, false));
                        }
                        @Override
                        public void onError(String error) {
                            android.util.Log.w(MainActivity.TAG,
                                "Save failed for " + filePath + ": " + error);
                        }
                    });
            }
        }
        activity.statusText.setText("All files saved");
    }

    void autoSaveModifiedFiles() {
        for (OpenFile openFile : activity.openFiles) {
            if (openFile.isModified()) {
                String filePath = openFile.getFilePath();
                activity.projectManager.writeFile(new File(filePath), openFile.getContent(),
                    new ProjectManager.OnFileOperationCallback() {
                        @Override
                        public void onSuccess(File file) {
                            openFile.setModified(false);
                            activity.runOnUiThread(() -> updateTabModified(filePath, false));
                        }
                        @Override
                        public void onError(String error) {
                            android.util.Log.w(MainActivity.TAG,
                                "Auto-save failed for " + filePath + ": " + error);
                        }
                    });
            }
        }
    }

    void saveAllModifiedFilesSync() {
        for (OpenFile openFile : activity.openFiles) {
            if (openFile.isModified()) {
                File file = new File(openFile.getFilePath());
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                    fos.write(openFile.getContent().getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
                    openFile.setModified(false);
                    updateTabModified(openFile.getFilePath(), false);
                } catch (IOException e) {
                    android.util.Log.w(MainActivity.TAG,
                        "Auto-save failed for " + file.getAbsolutePath(), e);
                }
            }
        }
    }

    void closeAllOpenTabs() {
        activity.runOnUiThread(() -> {
            activity.openFiles.clear();
            activity.openFilesByPath.clear();
            activity.tabLayout.removeAllTabs();
            activity.projectIndexer.clear();
            activity.currentEditorFile = "";
            if (activity.editorFragment != null && activity.editorFragment.isAdded()) {
                activity.editorFragment.setContent("", "");
            }
            activity.noEditorPlaceholder.setVisibility(View.VISIBLE);
        });
    }

    void updateWindowTitle() {
        String title = activity.getString(R.string.app_name);
        if (activity.currentProject != null) {
            title += " - " + activity.currentProject.getName();
        }
        activity.setTitle(title);
    }
}
