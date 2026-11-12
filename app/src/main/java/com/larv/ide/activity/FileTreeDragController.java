package com.larv.ide.activity;

import com.larv.ide.R;

import android.view.DragEvent;
import android.view.View;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.larv.ide.model.FileNode;
import com.larv.ide.model.OpenFile;
import com.larv.ide.project.ProjectManager;
import com.larv.ide.ui.adapter.FileTreeAdapter;

import java.io.File;

/**
 * Drag-and-drop moves inside the project tree: hit testing, drop highlight
 * and keeping open tabs/session paths in sync after a move. Extracted from
 * MainActivity.
 */
final class FileTreeDragController {

    private final MainActivity activity;

    private View highlightedDropView = null;
    private android.graphics.drawable.Drawable highlightedOriginalBackground = null;
    private boolean dropHandled = false;

    FileTreeDragController(MainActivity activity) {
        this.activity = activity;
    }

    void setup() {
        activity.fileTreeRecyclerView.setOnDragListener((v, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    updateDropHighlight(event);
                    return true;
                case DragEvent.ACTION_DROP:
                    FileNode target = findDropTarget(event);
                    if (target != null) {
                        FileNode source = activity.fileTreeAdapter.getDraggedNode();
                        if (source != null) {
                            performFileMove(source, target);
                            dropHandled = true;
                        }
                    }
                    clearDropHighlight();
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    clearDropHighlight();
                    dropHandled = false;
                    activity.fileTreeAdapter.setDraggedNode(null);
                    return true;
            }
            return false;
        });
    }

    private FileNode findDropTarget(DragEvent event) {
        View child = activity.fileTreeRecyclerView.findChildViewUnder(event.getX(), event.getY());
        if (child == null) return null;
        RecyclerView.ViewHolder holder =
            activity.fileTreeRecyclerView.getChildViewHolder(child);
        if (!(holder instanceof FileTreeAdapter.FileViewHolder)) return null;

        FileNode target = ((FileTreeAdapter.FileViewHolder) holder).getNode();
        FileNode source = activity.fileTreeAdapter.getDraggedNode();
        if (target == null || source == null) return null;
        if (target.getType() != FileNode.Type.DIRECTORY) return null;
        if (target.getPath().equals(source.getPath())) return null;
        if (source.getType() == FileNode.Type.DIRECTORY
            && target.getPath().startsWith(source.getPath() + File.separator)) return null;
        return target;
    }

    private void updateDropHighlight(DragEvent event) {
        FileNode target = findDropTarget(event);
        View newTarget = target != null
            ? activity.fileTreeRecyclerView.findChildViewUnder(event.getX(), event.getY())
            : null;
        if (newTarget == highlightedDropView) return;
        clearDropHighlight();
        highlightedDropView = newTarget;
        if (highlightedDropView != null) {
            highlightedOriginalBackground = highlightedDropView.getBackground();
            highlightedDropView.setBackgroundResource(R.drawable.drop_highlight);
        }
    }

    private void clearDropHighlight() {
        if (highlightedDropView != null) {
            highlightedDropView.setBackground(highlightedOriginalBackground);
            highlightedDropView = null;
            highlightedOriginalBackground = null;
        }
    }

    private void performFileMove(FileNode source, FileNode targetDir) {
        activity.projectManager.moveFile(
            new File(source.getPath()), new File(targetDir.getPath()),
            new ProjectManager.OnFileOperationCallback() {
                @Override
                public void onSuccess(File file) {
                    if (file.getAbsolutePath().equals(source.getPath())) {
                        activity.runOnUiThread(() -> Toast.makeText(activity,
                            "Already in this folder", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    updateOpenFilesAfterMove(source.getPath(), file.getAbsolutePath());
                    activity.runOnUiThread(() -> {
                        activity.statusText.setText("Moved: " + file.getName()
                            + " -> " + file.getParentFile().getName());
                        activity.projectManager.refreshFileTree();
                    });
                }

                @Override
                public void onError(String error) {
                    activity.runOnUiThread(() -> Toast.makeText(activity, error,
                        Toast.LENGTH_SHORT).show());
                }
            });
    }

    private void updateOpenFilesAfterMove(String oldPath, String newPath) {
        for (int i = 0; i < activity.openFiles.size(); i++) {
            OpenFile f = activity.openFiles.get(i);
            String fp = f.getFilePath();
            if (fp.equals(oldPath) || fp.startsWith(oldPath + File.separator)) {
                String updated = newPath + fp.substring(oldPath.length());
                f.setFilePath(updated);
                activity.openFilesByPath.remove(fp);
                activity.openFilesByPath.put(updated, f);
                if (activity.currentEditorFile.equals(fp)) {
                    activity.currentEditorFile = updated;
                    if (activity.editorFragment != null) {
                        activity.editorFragment.setContent(updated, f.getContent());
                    }
                }
                TabLayout.Tab tab = activity.tabLayout.getTabAt(i);
                if (tab != null) tab.setText((f.isModified() ? "* " : "") + f.getFileName());
            }
        }
        if (!activity.selectedDirectory.isEmpty()
            && (activity.selectedDirectory.equals(oldPath)
                || activity.selectedDirectory.startsWith(oldPath + File.separator))) {
            activity.selectedDirectory = activity.currentProject != null
                ? activity.currentProject.getPath() : "";
        }
    }
}
