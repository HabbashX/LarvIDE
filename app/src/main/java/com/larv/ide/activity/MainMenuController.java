package com.larv.ide.activity;

import com.larv.ide.R;

import android.content.Intent;
import android.view.Menu;
import android.view.View;
import android.widget.Toast;

import com.larv.ide.model.OpenFile;
import com.larv.ide.run.RunDispatcher;
import com.larv.ide.ui.fragment.EditorFragment;

import java.io.File;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Owns the top menu bar: popup construction, menu-item dispatch, View-menu
 * check state, language/toolchain installation and build-output cleaning.
 * Extracted from MainActivity.
 */
final class MainMenuController {

    private final MainActivity activity;

    MainMenuController(MainActivity activity) {
        this.activity = activity;
    }

    void showMenuBarPopup(View anchor, int menuRes,
                          BiPredicate<Integer, Integer> itemHandler,
                          Consumer<Menu> preparer) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(activity, anchor);
        popup.getMenuInflater().inflate(menuRes, popup.getMenu());
        if (preparer != null) {
            preparer.accept(popup.getMenu());
        }
        popup.setOnMenuItemClickListener(item -> {
            if (itemHandler != null && itemHandler.test(item.getItemId(), item.getGroupId())) {
                return true;
            }
            return false;
        });
        popup.show();
    }

    boolean onMenuBarItemSelected(int itemId, int groupId) {
        if (itemId == R.id.menu_new_project) {
            activity.projectActions.startProjectCreator();
            return true;
        } else if (itemId == R.id.menu_open_project) {
            activity.projectActions.showOpenProjectDialog();
            return true;
        } else if (itemId == R.id.menu_new_file) {
            if (activity.currentProject == null) {
                Toast.makeText(activity, "Open a project first", Toast.LENGTH_SHORT).show();
            } else {
                activity.projectActions.showNewFileDialog();
            }
            return true;
        } else if (itemId == R.id.menu_new_folder) {
            if (activity.currentProject == null) {
                Toast.makeText(activity, "Open a project first", Toast.LENGTH_SHORT).show();
            } else {
                activity.projectActions.showNewFolderDialog();
            }
            return true;
        } else if (itemId == R.id.menu_save) {
            activity.editorTabs.saveCurrentFile();
            return true;
        } else if (itemId == R.id.menu_save_all) {
            activity.editorTabs.saveAllFiles();
            return true;
        } else if (itemId == R.id.menu_close_project) {
            activity.projectManager.closeProject();
            return true;
        } else if (itemId == R.id.menu_undo) {
            runOnCurrentEditor(f -> f.execAction("undo"));
            return true;
        } else if (itemId == R.id.menu_redo) {
            runOnCurrentEditor(f -> f.execAction("redo"));
            return true;
        } else if (itemId == R.id.menu_find) {
            runOnCurrentEditor(f -> f.execAction("find"));
            return true;
        } else if (itemId == R.id.menu_search_project) {
            activity.projectActions.showProjectSearchDialog();
            return true;
        } else if (itemId == R.id.menu_toggle_project) {
            activity.toggleLeftWindow();
            return true;
        } else if (itemId == R.id.menu_toggle_terminal) {
            activity.toggleBottomWindow();
            return true;
        } else if (itemId == R.id.menu_expand_editor) {
            activity.toggleMaximizeEditor();
            return true;
        } else if (itemId == R.id.menu_run) {
            activity.runController.compileAndRun();
            return true;
        } else if (itemId == R.id.menu_compile) {
            activity.runController.compileAndRun();
            return true;
        } else if (itemId == R.id.menu_stop) {
            activity.runController.stopRunningProcess();
            return true;
        } else if (itemId == R.id.menu_open_terminal) {
            activity.runController.openEmbeddedTerminal();
            return true;
        } else if (itemId == R.id.menu_languages) {
            com.larv.ide.ui.dialog.LanguagesDialog.show(activity,
                activity.currentProject != null
                    ? activity.currentProject.getRootDir() : activity.getFilesDir(),
                (pkg) -> {
                    if (activity.embeddedBackend == null
                            || !activity.embeddedBackend.isAvailable()) {
                        Toast.makeText(activity,
                            "Linux runtime not ready - download it first",
                            Toast.LENGTH_LONG).show();
                        activity.showEmbeddedSetupDialog();
                        return;
                    }
                    try {
                        RunDispatcher.RunStreams rs = activity.runController.openRunTerminal(
                            "Installing " + pkg + " - watch the Run tab");
                        activity.runController.writeTerm(rs.programOut(),
                            "$ pkg update -y && pkg install -y " + pkg + "\n");
                        activity.compilerExecutor.execute(() -> {
                            try {
                                activity.embeddedBackend.executeCapture(
                                    new com.larv.ide.run.backend.ExecRequest(
                                        java.util.Arrays.asList("pkg", "update", "-y", "&&", "pkg", "install", "-y", pkg),
                                        null, false),
                                    new com.larv.ide.run.backend.embedded.EmbeddedLinuxBackend.OutputSink() {
                                        @Override public void onStdout(byte[] data, int len) {
                                            com.larv.ide.run.backend.embedded.EmbeddedLinuxBackend.copyTo(
                                                rs.programOut(), data, len);
                                        }
                                        @Override public void onStderr(byte[] data, int len) {
                                            com.larv.ide.run.backend.embedded.EmbeddedLinuxBackend.copyTo(
                                                rs.programOut(), data, len);
                                        }
                                        @Override public void onExit(int exitCode) {
                                            if (exitCode == 0) {
                                                activity.prefs.edit().putBoolean(
                                                    com.larv.ide.setup.SetupConfig.toolchainFlag(
                                                        pkg), true).apply();
                                            }
                                            activity.runController.writeTerm(rs.programOut(),
                                                "\nInstall finished (exit " + exitCode + ")\n");
                                            activity.runController.closeTermStreams(
                                                rs.programOut(), rs.stdinOut());
                                        }
                                    });
                            } catch (Exception e) {
                                activity.runController.writeTerm(rs.programOut(),
                                    "Install failed: " + e.getMessage() + "\n");
                                activity.runController.closeTermStreams(
                                    rs.programOut(), rs.stdinOut());
                            }
                        });
                        Toast.makeText(activity, "Installing " + pkg + " - watch the Run tab",
                            Toast.LENGTH_LONG).show();
                    } catch (Exception ex) {
                        Toast.makeText(activity, "Linux runtime not ready",
                            Toast.LENGTH_LONG).show();
                    }
                });
            return true;
        } else if (itemId == R.id.menu_settings) {
            activity.showSettingsDialog();
            return true;
        } else if (itemId == R.id.menu_about) {
            activity.showAboutDialog();
            return true;
        } else if (itemId == R.id.menu_help) {
            activity.showHelpDialog();
            return true;
        }
        return false;
    }

    private void runOnCurrentEditor(Consumer<EditorFragment> action) {
        if (!activity.currentEditorFile.isEmpty() && activity.editorFragment != null) {
            action.accept(activity.editorFragment);
        }
    }

    void syncViewMenuState(Menu menu) {
        android.view.MenuItem projectItem = menu.findItem(R.id.menu_toggle_project);
        if (projectItem != null) {
            projectItem.setChecked(activity.leftWindowVisible);
        }
        android.view.MenuItem terminalItem = menu.findItem(R.id.menu_toggle_terminal);
        if (terminalItem != null) {
            terminalItem.setChecked(activity.bottomWindowVisible);
        }
        android.view.MenuItem expandItem = menu.findItem(R.id.menu_expand_editor);
        if (expandItem != null) {
            expandItem.setChecked(activity.editorMaximized);
        }
    }

    private void clearBuildOutput() {
        // Prefix .class output lives next to sources; dex scratch is per-run.
        // Full clean = drop dex scratch dirs.
        activity.compilerExecutor.execute(() -> {
            File dexScratch = new File(activity.getCacheDir(), "dexoutput");
            MainDialogs.deleteRecursive(dexScratch);
            dexScratch.mkdirs();
            activity.runOnUiThread(() -> activity.statusText.setText("Build output cleared"));
        });
    }
}
