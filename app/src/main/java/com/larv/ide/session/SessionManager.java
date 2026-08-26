package com.larv.ide.session;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.material.tabs.TabLayout;
import com.larv.ide.completion.ProjectIndexer;
import com.larv.ide.model.OpenFile;
import com.larv.ide.model.Project;
import com.larv.ide.project.ProjectManager;
import com.larv.ide.ui.fragment.EditorFragment;

import org.jetbrains.annotations.Contract;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public class SessionManager {

    private static final String TAG = "SessionManager";

    public interface Host {
        Project currentProject();
        List<OpenFile> openFiles();
        Map<String, OpenFile> filesByPath();
        String activeFile();
        EditorFragment editorFragment();
        TabLayout tabLayout();
        void ensureEditorFragment();
        void hideEditorPlaceholders();
        void switchToTab(int index);
        ProjectManager projectManager();
        ProjectIndexer indexer();
        ExecutorService executor();
        void runOnUiThread(Runnable action);
    }

    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable saveRunnable = this::saveNow;

    private boolean restoring = false;
    private int restoreRemaining = 0;
    private String pendingCursorPositions = null;

    public SessionManager(Host host) {
        this.host = host;
    }

    public void scheduleSave() {
        handler.removeCallbacks(saveRunnable);
        handler.postDelayed(saveRunnable, 800);
    }

    public void cancelSave() {
        handler.removeCallbacks(saveRunnable);
    }

    private File sessionFile(Project project) {
        File dir = new File(project.getRootDir(), ".larv");
        return new File(dir, "session.json");
    }

    public void saveNow() {
        final Project project = host.currentProject();
        if (project == null || host.openFiles().isEmpty() || restoring) return;
        host.executor().execute(() -> {
            try {
                JSONObject root = new JSONObject();
                JSONArray tabs = new JSONArray();
                JSONObject cursors = new JSONObject();
                JSONObject buffers = new JSONObject();
                for (OpenFile f : host.openFiles()) {
                    tabs.put(f.getFilePath());
                    if (f.getCursorLine() > 0) {
                        cursors.put(f.getFilePath(), new JSONObject()
                            .put("lineNumber", f.getCursorLine())
                            .put("column", f.getCursorColumn()));
                    }
                    if (f.isModified()) {
                        buffers.put(f.getFilePath(), f.getContent());
                    }
                }
                root.put("tabs", tabs);
                root.put("active", host.activeFile());
                root.put("cursors", cursors);
                root.put("buffers", buffers);
                File file = sessionFile(project);
                file.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                Log.w(TAG, "saveSession failed", e);
            }
        });
    }

    public void reset() {
        restoring = false;
        restoreRemaining = 0;
        cancelSave();
    }

    public void restore(Project project) {
        host.executor().execute(() -> {
            String json = readSessionFile(project);
            if (json == null || json.isEmpty()) return;

            try {
                JSONObject root = new JSONObject(json);
                List<String> tabs = new ArrayList<>();
                JSONArray tabArr = root.optJSONArray("tabs");
                if (tabArr != null) {
                    for (int i = 0; i < tabArr.length(); i++) {
                        tabs.add(tabArr.getString(i));
                    }
                }
                if (tabs.isEmpty()) return;
                String active = root.optString("active", "");
                JSONObject cursors = root.optJSONObject("cursors");
                JSONObject buffers = root.optJSONObject("buffers");

                JSONObject posObj = new JSONObject();
                if (cursors != null) {
                    java.util.Iterator<String> keyIt = cursors.keys();
                    while (keyIt.hasNext()) {
                        String path = keyIt.next();
                        JSONObject p = cursors.getJSONObject(path);
                        posObj.put(path, new JSONObject()
                            .put("lineNumber", p.optInt("lineNumber", 1))
                            .put("column", p.optInt("column", 1)));
                    }
                }
                final String positionsJson = posObj.toString();

                List<String> openOrder = new ArrayList<>(tabs);
                final String activePath = active.isEmpty() ? tabs.get(0) : active;
                restoring = true;
                int existing = 0;
                for (String path : openOrder) {
                    if (new File(path).exists()) existing++;
                }
                restoreRemaining = existing;
                if (existing == 0) {
                    restoring = false;
                    return;
                }
                for (String path : openOrder) {
                    final File f = new File(path);
                    if (!f.exists()) continue;
                    final String buffered = buffers != null ? buffers.optString(path, null) : null;
                    final boolean hasBuffer = buffered != null;
                    host.projectManager().readFile(f, new ProjectManager.OnFileReadCallback() {
                        @Override
                        public void onContent(String content) {
                            String useContent = hasBuffer ? buffered : content;
                            host.runOnUiThread(() -> openRestoredTab(path, f,
                                useContent, hasBuffer, activePath));
                        }

                        @Override
                        public void onError(String error) {
                            finishRestoreTab(activePath);
                        }
                    });
                }
                host.runOnUiThread(() -> {
                    EditorFragment fragment = host.editorFragment();
                    if (fragment != null) {
                        fragment.setCursorPositions(positionsJson);
                    } else {
                        pendingCursorPositions = positionsJson;
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "restoreSession parse failed", e);
            }
        });
    }

    private void openRestoredTab(String path, File f, String content, boolean hasBuffer,
                                 String activePath) {
        OpenFile existing = host.filesByPath().get(path);
        if (existing == null) {
            OpenFile created = new OpenFile(path, content);
            created.setModified(hasBuffer);
            host.openFiles().add(created);
            host.filesByPath().put(path, created);
            int index = host.openFiles().size() - 1;
            TabLayout.Tab tab = host.tabLayout().newTab().setText(f.getName());
            host.tabLayout().addTab(tab, index, false);
            host.executor().execute(() -> host.indexer().indexFile(created));
        } else if (hasBuffer) {
            existing.setContent(content);
        }
        finishRestoreTab(activePath);
    }

    private void finishRestoreTab(String activePath) {
        host.runOnUiThread(() -> {
            restoreRemaining--;
            if (restoreRemaining > 0) {
                return;
            }
            restoring = false;
            host.ensureEditorFragment();
            host.hideEditorPlaceholders();
            OpenFile target = host.filesByPath().get(activePath);
            if (target != null) {
                host.switchToTab(host.openFiles().indexOf(target));
            }
        });
    }

    private String readSessionFile(Project project) {
        try {
            File file = sessionFile(project);
            if (!file.exists()) return null;
            StringBuilder sb = new StringBuilder();
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "readSession failed", e);
            return null;
        }
    }

    public boolean hasPendingCursorPositions() {
        return pendingCursorPositions != null;
    }

    public String consumePendingCursorPositions() {
        String p = pendingCursorPositions;
        pendingCursorPositions = null;
        return p;
    }

    @Nullable
    @Contract(pure = true)
    @SuppressWarnings("unused")
    private static Context unusedContextReference() {
        return null;
    }
}
