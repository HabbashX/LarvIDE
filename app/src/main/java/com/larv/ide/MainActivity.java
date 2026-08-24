package com.larv.ide;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.text.InputType;
import android.util.Log;
import android.view.DragEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.gson.Gson;
import com.larv.ide.build.DependencyResolver;
import com.larv.ide.compiler.Dexer;
import com.larv.ide.compiler.JavaCompiler;
import com.larv.ide.compiler.JavaRunner;
import com.larv.ide.compiler.JsRunner;
import com.larv.ide.compiler.PyRunner;
import com.larv.ide.completion.CompletionItem;
import com.larv.ide.completion.ProjectIndexer;
import com.larv.ide.model.FileNode;
import com.larv.ide.model.Diagnostic;
import com.larv.ide.model.OpenFile;
import com.larv.ide.model.Project;
import com.larv.ide.project.ProjectManager;
import com.larv.ide.project.ProjectRecognizer;
import com.larv.ide.ui.adapter.BottomPanelAdapter;
import com.larv.ide.ui.adapter.FileTreeAdapter;
import com.larv.ide.ui.fragment.EditorFragment;
import com.larv.ide.ui.fragment.OutputFragment;

import org.json.JSONArray;
import org.json.JSONObject;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity
        implements ProjectManager.OnProjectChangeListener,
        FileTreeAdapter.OnFileClickListener,
        EditorFragment.EditorListener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_STORAGE_PERMISSION = 1001;
    private static final String PREFS_NAME = "larv_ide";
    private static final String PREF_LAST_PROJECT = "last_project_path";

    private SharedPreferences prefs;

    private android.widget.TextView menuFile;
    private android.widget.TextView menuEdit;
    private android.widget.TextView menuSearch;
    private android.widget.TextView menuView;
    private android.widget.TextView menuBuild;
    private android.widget.TextView menuSettings;

    private View leftToolWindowContent;
    private FrameLayout projectToolWindow;
    private ImageButton btnNewFile;
    private ImageButton btnNewFolder;
    private ImageButton btnRefreshProject;
    private ImageButton btnCollapseAll;

    private FrameLayout editorContainer;
    private View noEditorPlaceholder;
    private View welcomeView;
    private TabLayout tabLayout;
    private ImageButton newTabButton;
    private ImageButton btnSplitEditor;
    private ImageButton btnRun;
    private ImageButton btnCloseProjectWindow;
    private ImageButton btnCloseBottomWindow;

    private FrameLayout bottomToolWindow;
    private TabLayout bottomTabLayout;
    private ViewPager2 bottomViewPager;
    private BottomPanelAdapter bottomPanelAdapter;

    private android.widget.TextView statusText;
    private android.widget.TextView statusPosition;

    private View leftResizer;
    private View bottomResizer;

    private final Handler autosaveHandler = new Handler(Looper.getMainLooper());
    private final Runnable autosaveRunnable = this::autoSaveModifiedFiles;
    private final Handler sessionHandler = new Handler(Looper.getMainLooper());
    private final Runnable sessionSaveRunnable = this::saveSessionNow;

    private androidx.recyclerview.widget.RecyclerView fileTreeRecyclerView;
    private FileTreeAdapter fileTreeAdapter;

    private EditorFragment editorFragment;
    private String currentEditorFile = "";
    private final java.util.concurrent.atomic.AtomicBoolean typeCheckRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private int lastStatusLine = -1;
    private int lastStatusColumn = -1;

    private final Handler typeCheckHandler = new Handler(Looper.getMainLooper());
    private final Runnable syntaxCheckRunnable = this::runSyntaxCheck;
    private final Runnable typeCheckRunnable = this::runTypeCheck;
    private ProjectManager projectManager;
    private JavaCompiler javaCompiler;
    private Dexer dexer;
    private DependencyResolver dependencyResolver;
    private JsRunner jsRunner;
    private PyRunner pyRunner;
    private JavaRunner javaRunner;
    private ProjectIndexer projectIndexer;
    private final ExecutorService compilerExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService indexerExecutor = Executors.newSingleThreadExecutor();
    private Project currentProject;
    private final List<OpenFile> openFiles = new ArrayList<>();
    private final Map<String, OpenFile> openFilesByPath = new HashMap<>();
    private static final Gson GSON = new Gson();
    private volatile boolean isCompiling = false;
    private String selectedDirectory = "";
    private boolean leftWindowVisible = true;
    private boolean bottomWindowVisible = true;
    private boolean editorMaximized = false;
    private View highlightedDropView = null;
    private android.graphics.drawable.Drawable highlightedOriginalBackground = null;
    private boolean dropHandled = false;
    private String pendingCursorPositions = null;
    private boolean restoringSession = false;
    private int restoreRemaining = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initServices();
        setupListeners();
        checkPermissions();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        handleIntent(getIntent());
        restoreLastProject();
        showWelcome(currentProject == null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getData() != null) {
            Uri uri = intent.getData();
            String path = uri.getPath();
            if (path != null && path.endsWith(".java")) {
                File file = new File(path);
                if (file.exists()) {
                    openFileInEditor(file);
                }
            }
        }
    }

    private void restoreLastProject() {
        if (prefs == null) return;
        String savedPath = prefs.getString(PREF_LAST_PROJECT, "");
        if (savedPath.isEmpty()) return;
        File dir = new File(savedPath);
        if (dir.exists() && dir.isDirectory()) {
            projectManager.openProject(new Project(dir.getName(), dir.getAbsolutePath()));
        }
    }

    private void initViews() {
        menuFile = findViewById(R.id.menuFile);
        menuEdit = findViewById(R.id.menuEdit);
        menuSearch = findViewById(R.id.menuSearch);
        menuView = findViewById(R.id.menuView);
        menuBuild = findViewById(R.id.menuBuild);
        menuSettings = findViewById(R.id.menuSettings);

        leftToolWindowContent = findViewById(R.id.leftToolWindowContent);
        projectToolWindow = findViewById(R.id.projectToolWindow);
        btnNewFile = findViewById(R.id.btnNewFile);
        btnNewFolder = findViewById(R.id.btnNewFolder);
        btnRefreshProject = findViewById(R.id.btnRefreshProject);
        btnCollapseAll = findViewById(R.id.btnCollapseAll);
        btnCloseProjectWindow = findViewById(R.id.btnCloseProjectWindow);
        leftResizer = findViewById(R.id.leftResizer);

        editorContainer = findViewById(R.id.editorContainer);
        noEditorPlaceholder = findViewById(R.id.noEditorPlaceholder);
        welcomeView = findViewById(R.id.welcomeView);
        tabLayout = findViewById(R.id.tabLayout);
        newTabButton = findViewById(R.id.newTabButton);
        btnSplitEditor = findViewById(R.id.btnSplitEditor);
        btnRun = findViewById(R.id.btnRun);

        bottomToolWindow = findViewById(R.id.bottomToolWindow);
        bottomTabLayout = findViewById(R.id.bottomTabLayout);
        bottomViewPager = findViewById(R.id.bottomViewPager);
        btnCloseBottomWindow = findViewById(R.id.btnCloseBottomWindow);
        bottomResizer = findViewById(R.id.bottomResizer);

        statusText = findViewById(R.id.statusText);
        statusPosition = findViewById(R.id.statusPosition);

        fileTreeRecyclerView = findViewById(R.id.fileTreeRecyclerView);
        fileTreeAdapter = new FileTreeAdapter(this);
        fileTreeRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        fileTreeRecyclerView.setAdapter(fileTreeAdapter);
        setupFileTreeDragAndDrop();

        bottomPanelAdapter = new BottomPanelAdapter(this);
        bottomViewPager.setAdapter(bottomPanelAdapter);
        bottomViewPager.setOffscreenPageLimit(3);

        new TabLayoutMediator(bottomTabLayout, bottomViewPager, (tab, position) -> {
            String title;
            switch (position) {
                case 1: title = getString(R.string.errors_title); break;
                case 2: title = "Preview"; break;
                default: title = getString(R.string.run_code); break;
            }
            tab.setText(title);
        }).attach();

        noEditorPlaceholder.setVisibility(View.VISIBLE);
    }

    private void initServices() {
        projectManager = new ProjectManager(getApplicationContext());
        projectManager.setListener(this);

        javaCompiler = new JavaCompiler(getApplicationContext());
        dexer = new Dexer(getApplicationContext());
        dependencyResolver = new DependencyResolver(new File(getFilesDir(), "m2"));
        jsRunner = new JsRunner();
        pyRunner = new PyRunner(getApplicationContext());
        javaRunner = new JavaRunner(getApplicationContext());
        projectIndexer = new ProjectIndexer();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupListeners() {
        btnNewFile.setOnClickListener(v -> {
            if (currentProject == null) {
                Toast.makeText(this, "Open a project first", Toast.LENGTH_SHORT).show();
            } else {
                showNewFileDialog();
            }
        });
        btnNewFolder.setOnClickListener(v -> {
            if (currentProject == null) {
                Toast.makeText(this, "Open a project first", Toast.LENGTH_SHORT).show();
            } else {
                showNewFolderDialog();
            }
        });
        btnRefreshProject.setOnClickListener(v -> projectManager.refreshFileTree());
        btnCollapseAll.setOnClickListener(v -> fileTreeAdapter.collapseAll());

        newTabButton.setOnClickListener(v -> {
            if (currentProject == null) {
                Toast.makeText(this, "Open a project first", Toast.LENGTH_SHORT).show();
            } else {
                showNewFileDialog();
            }
        });
        btnSplitEditor.setOnClickListener(v -> toggleMaximizeEditor());
        btnRun.setOnClickListener(v -> compileAndRun());
        btnCloseProjectWindow.setOnClickListener(v -> closeLeftWindow());
        btnCloseBottomWindow.setOnClickListener(v -> closeBottomWindow());

        findViewById(R.id.btnWelcomeNewProject).setOnClickListener(v -> showNewProjectDialog());
        findViewById(R.id.btnWelcomeOpenProject).setOnClickListener(v -> showOpenProjectDialog());

        menuFile.setOnClickListener(v -> showMenuBarPopup(v, R.menu.menu_file, this::onMenuBarItemSelected, null));
        menuEdit.setOnClickListener(v -> showMenuBarPopup(v, R.menu.menu_edit, this::onMenuBarItemSelected, null));
        menuSearch.setOnClickListener(v -> showMenuBarPopup(v, R.menu.menu_search, this::onMenuBarItemSelected, null));
        menuView.setOnClickListener(v -> showMenuBarPopup(v, R.menu.menu_view, this::onMenuBarItemSelected,
            this::syncViewMenuState));
        menuBuild.setOnClickListener(v -> showMenuBarPopup(v, R.menu.menu_build, this::onMenuBarItemSelected, null));
        menuSettings.setOnClickListener(v -> showMenuBarPopup(v, R.menu.menu_settings, this::onMenuBarItemSelected, null));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                switchToEditorTab(tab.getPosition());
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                tab.view.setOnLongClickListener(v -> {
                    showTabCloseMenu(tab.getPosition(), v);
                    return true;
                });
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        leftResizer.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float x = event.getRawX();
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int newWidth = (int) x;
                ViewGroup.LayoutParams params = leftToolWindowContent.getLayoutParams();
                params.width = Math.max(140, Math.min(screenWidth - 300, newWidth));
                leftToolWindowContent.setLayoutParams(params);
                return true;
            }
            return false;
        });

        bottomResizer.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float y = event.getRawY();
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                ViewGroup.LayoutParams params = bottomToolWindow.getLayoutParams();
                params.height = Math.max(100, Math.min(screenHeight / 2, screenHeight - (int) y));
                bottomToolWindow.setLayoutParams(params);
                return true;
            }
            return false;
        });
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onProjectOpened(@NonNull Project project) {
        currentProject = project;
        selectedDirectory = project.getPath();
        if (prefs != null) {
            prefs.edit().putString(PREF_LAST_PROJECT, project.getPath()).apply();
        }
        runOnUiThread(() -> {
            bottomToolWindow.setVisibility(editorMaximized ? View.GONE : View.VISIBLE);
            bottomWindowVisible = true;
            updateWindowTitle();
            showWelcomeStatus(true);
            showWelcome(false);
        });
        restoreSession(project);
        indexerExecutor.execute(() -> {
            ProjectRecognizer.Detection detection = ProjectRecognizer.detect(project.getRootDir(), "");
            if (!detection.languages.isEmpty()) {
                String joined = String.join(", ", detection.languages);
                runOnUiThread(() -> statusText.setText("Project: " + joined));
            }
        });
    }

    private File sessionFile(Project project) {
        File dir = new File(project.getRootDir(), ".larv");
        return new File(dir, "session.json");
    }

    private void saveSessionNow() {
        final Project project = currentProject;
        if (project == null || openFiles.isEmpty() || restoringSession) return;
        indexerExecutor.execute(() -> {
            try {
                JSONObject root = new JSONObject();
                JSONArray tabs = new JSONArray();
                JSONObject cursors = new JSONObject();
                JSONObject buffers = new JSONObject();
                for (OpenFile f : openFiles) {
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
                root.put("active", currentEditorFile);
                root.put("cursors", cursors);
                root.put("buffers", buffers);
                File file = sessionFile(project);
                file.getParentFile().mkdirs();
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                    fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                android.util.Log.w(TAG, "saveSession failed", e);
            }
        });
    }

    private void restoreSession(Project project) {
        indexerExecutor.execute(() -> {
            String json = null;
            try {
                File file = sessionFile(project);
                if (file.exists()) {
                    StringBuilder sb = new StringBuilder();
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = fis.read(buf)) > 0) {
                            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        }
                    }
                    json = sb.toString();
                }
            } catch (Exception e) {
                android.util.Log.w(TAG, "restoreSession read failed", e);
            }
            if (json == null || json.isEmpty()) return;

            try {
                JSONObject root = new JSONObject(json);
                List<String> tabs = new ArrayList<>();
                org.json.JSONArray tabArr = root.optJSONArray("tabs");
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

                final List<String> openOrder = new ArrayList<>(tabs);
                final String activePath = active.isEmpty() ? tabs.get(0) : active;
                restoringSession = true;
                int existing_ = 0;
                for (String path : openOrder) {
                    if (new File(path).exists()) existing_++;
                }
                restoreRemaining = existing_;
                if (existing_ == 0) {
                    restoringSession = false;
                    return;
                }
                for (String path : openOrder) {
                    final File f = new File(path);
                    if (!f.exists()) {
                        continue;
                    }
                    final String buffered = buffers != null ? buffers.optString(path, null) : null;
                    final boolean hasBuffer = buffered != null;
                    projectManager.readFile(f, new ProjectManager.OnFileReadCallback() {
                        @Override
                        public void onContent(String content) {
                            String useContent = hasBuffer ? buffered : content;
                            runOnUiThread(() -> {
                                OpenFile existing = findOpenFile(path);
                                if (existing == null) {
                                    OpenFile created = new OpenFile(path, useContent);
                                    created.setModified(hasBuffer);
                                    openFiles.add(created);
                                    openFilesByPath.put(path, created);
                                    int index = openFiles.size() - 1;
                                    TabLayout.Tab tab = tabLayout.newTab().setText(f.getName());
                                    tabLayout.addTab(tab, index, false);
                                    indexerExecutor.execute(() -> projectIndexer.indexFile(created));
                                } else if (hasBuffer) {
                                    existing.setContent(useContent);
                                }
                                finishRestoreTab(activePath);
                            });
                        }

                        @Override
                        public void onError(String error) {
                            finishRestoreTab(activePath);
                        }
                    });
                }
                runOnUiThread(() -> {
                    if (editorFragment != null) {
                        editorFragment.setCursorPositions(positionsJson);
                    } else {
                        pendingCursorPositions = positionsJson;
                    }
                });
            } catch (Exception e) {
                android.util.Log.w(TAG, "restoreSession parse failed", e);
            }
        });
    }

    private void finishRestoreTab(String activePath) {
        runOnUiThread(() -> {
            restoreRemaining--;
            if (restoreRemaining > 0) {
                return;
            }
            restoringSession = false;
            ensureEditorFragment();
            noEditorPlaceholder.setVisibility(View.GONE);
            welcomeView.setVisibility(View.GONE);
            OpenFile target = findOpenFile(activePath);
            if (target != null) {
                switchToEditorTab(openFiles.indexOf(target));
            }
        });
    }

    @Override
    public void onProjectClosed() {
        currentProject = null;
        restoringSession = false;
        restoreRemaining = 0;
        sessionHandler.removeCallbacks(sessionSaveRunnable);
        openFiles.clear();
        openFilesByPath.clear();
        javaCompiler.resetCheckState();
        typeCheckHandler.removeCallbacksAndMessages(null);
        if (editorFragment != null) {
            if (editorFragment.isAdded()) {
                getSupportFragmentManager().beginTransaction()
                    .remove(editorFragment)
                    .commitAllowingStateLoss();
            }
            editorFragment = null;
        }
        currentEditorFile = "";
        selectedDirectory = "";
        if (prefs != null) {
            prefs.edit().remove(PREF_LAST_PROJECT).apply();
        }
        runOnUiThread(() -> {
            bottomToolWindow.setVisibility(View.GONE);
            bottomWindowVisible = false;
            tabLayout.removeAllTabs();
            editorContainer.removeAllViews();
            noEditorPlaceholder.setVisibility(View.VISIBLE);
            updateWindowTitle();
            statusText.setText("No project");
            showWelcome(true);
        });
    }

    @Override
    public void onFileTreeUpdated(List<FileNode> rootNodes) {
        runOnUiThread(() -> {
            fileTreeAdapter.setRootNodes(rootNodes);
            fileTreeAdapter.expandPath(selectedDirectory);
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onFileClick(@NonNull FileNode node) {
        if (node.getType() == FileNode.Type.DIRECTORY) {
            selectedDirectory = node.getPath();
            fileTreeAdapter.toggleExpansion(node);
        } else if (node.isJavaFile()) {
            openFileInEditor(new File(node.getPath()));
        }
    }

    @Override
    public void onFileMoreClick(FileNode node, View anchor) {
        showFileContextMenu(node, anchor);
    }

    private void setupFileTreeDragAndDrop() {
        fileTreeRecyclerView.setOnDragListener((v, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    updateDropHighlight(event);
                    return true;
                case DragEvent.ACTION_DROP:
                    FileNode target = findDropTarget(event);
                    if (target != null) {
                        FileNode source = fileTreeAdapter.getDraggedNode();
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
                    fileTreeAdapter.setDraggedNode(null);
                    return true;
            }
            return false;
        });
    }

    private FileNode findDropTarget(DragEvent event) {
        View child = fileTreeRecyclerView.findChildViewUnder(event.getX(), event.getY());
        if (child == null) return null;
        RecyclerView.ViewHolder holder = fileTreeRecyclerView.getChildViewHolder(child);
        if (!(holder instanceof FileTreeAdapter.FileViewHolder)) return null;

        FileNode target = ((FileTreeAdapter.FileViewHolder) holder).getNode();
        FileNode source = fileTreeAdapter.getDraggedNode();
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
            ? fileTreeRecyclerView.findChildViewUnder(event.getX(), event.getY())
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
        projectManager.moveFile(new File(source.getPath()), new File(targetDir.getPath()),
            new ProjectManager.OnFileOperationCallback() {
                @Override
                public void onSuccess(File file) {
                    if (file.getAbsolutePath().equals(source.getPath())) {
                        runOnUiThread(() ->
                            Toast.makeText(MainActivity.this, "Already in this folder", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    updateOpenFilesAfterMove(source.getPath(), file.getAbsolutePath());
                    runOnUiThread(() -> {
                        statusText.setText("Moved: " + file.getName() + " -> " + file.getParentFile().getName());
                        projectManager.refreshFileTree();
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show());
                }
            });
    }

    private void updateOpenFilesAfterMove(String oldPath, String newPath) {
        for (int i = 0; i < openFiles.size(); i++) {
            OpenFile f = openFiles.get(i);
            String fp = f.getFilePath();
            if (fp.equals(oldPath) || fp.startsWith(oldPath + File.separator)) {
                String updated = newPath + fp.substring(oldPath.length());
                f.setFilePath(updated);
                openFilesByPath.remove(fp);
                openFilesByPath.put(updated, f);
                if (currentEditorFile.equals(fp)) {
                    currentEditorFile = updated;
                    if (editorFragment != null) {
                        editorFragment.setContent(updated, f.getContent());
                    }
                }
                TabLayout.Tab tab = tabLayout.getTabAt(i);
                if (tab != null) tab.setText((f.isModified() ? "* " : "") + f.getFileName());
            }
        }
        if (!selectedDirectory.isEmpty()
            && (selectedDirectory.equals(oldPath) || selectedDirectory.startsWith(oldPath + File.separator))) {
            selectedDirectory = currentProject != null ? currentProject.getPath() : "";
        }
    }


    @Override
    public void onContentChange(String file, String content) {
        OpenFile openFile = findOpenFile(file);
        if (openFile != null) {
            boolean changed = !openFile.getContent().equals(content);
            openFile.setContent(content);
            if (changed) {
                openFile.setModified(true);
                String fileName = new File(file).getName();
                runOnUiThread(() -> {
                    updateTabModified(file, true);
                    statusText.setText("Updated: " + fileName);
                    if (prefs.getBoolean("autosaveEnabled", true)) {
                        autosaveHandler.removeCallbacks(autosaveRunnable);
                        autosaveHandler.postDelayed(autosaveRunnable, 1000);
                    }
                });
                scheduleTypeCheck();
            }
        }
        sessionHandler.removeCallbacks(sessionSaveRunnable);
        sessionHandler.postDelayed(sessionSaveRunnable, 800);
    }

    @Override
    public void onCursorChange(int line, int column) {
        OpenFile openFile = findOpenFile(currentEditorFile);
        if (openFile != null) {
            openFile.setCursorLine(line);
            openFile.setCursorColumn(column);
        }
        if (lastStatusLine != line || lastStatusColumn != column) {
            lastStatusLine = line;
            lastStatusColumn = column;
            runOnUiThread(() -> statusPosition.setText("Ln " + line + ", Col " + column));
        }
    }

    @Override
    public void onCompletionsRequested(String file, int line, int column, String prefix,
                                       String memberOf, int requestId,
                                       @NonNull EditorFragment.CompletionCallback callback) {
        OpenFile openFile = findOpenFile(file);
        String content = openFile != null ? openFile.getContent() : null;
        indexerExecutor.execute(() -> {
            List<CompletionItem> completions = projectIndexer.getCompletions(
                prefix == null ? "" : prefix, file, line, column, memberOf, content);
            callback.onCompletions(completions);
        });
    }

    @Override
    public String onImportCandidatesRequested(String className) {
        return projectIndexer.findImportCandidates(className);
    }

    @Override
    public void onEditorReady() {
        if (editorFragment != null) {
            if (pendingCursorPositions != null) {
                editorFragment.setCursorPositions(pendingCursorPositions);
                pendingCursorPositions = null;
            }
            editorFragment.applyEditorSettings(
                prefs.getInt("editorFontSize", 14),
                prefs.getInt("editorTabSize", 4),
                prefs.getBoolean("editorLineNumbers", true),
                prefs.getBoolean("editorWordWrap", false));
        }
        if (!currentEditorFile.isEmpty()) {
            OpenFile openFile = findOpenFile(currentEditorFile);
            if (openFile != null && editorFragment != null) {
                editorFragment.setContent(currentEditorFile, openFile.getContent());
            }
        }
    }

    private void openFileInEditor(@NonNull File file) {
        String filePath = file.getAbsolutePath();

        OpenFile existing = findOpenFile(filePath);
        if (existing != null) {
            switchToEditorTab(openFiles.indexOf(existing));
            return;
        }

        projectManager.readFile(file, new ProjectManager.OnFileReadCallback() {
            @Override
            public void onContent(String content) {
                runOnUiThread(() -> {
                    OpenFile openFile = new OpenFile(filePath, content);
                    openFiles.add(openFile);
                    openFilesByPath.put(filePath, openFile);

                    int index = openFiles.size() - 1;
                    TabLayout.Tab tab = tabLayout.newTab().setText(file.getName());
                    tabLayout.addTab(tab, index, true);

                    ensureEditorFragment();
                    tabLayout.selectTab(tab);
                    noEditorPlaceholder.setVisibility(View.GONE);
                    welcomeView.setVisibility(View.GONE);
                    indexerExecutor.execute(() -> projectIndexer.indexFile(openFile));
                    statusText.setText(file.getName() + " - Loading");
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void ensureEditorFragment() {
        if (editorFragment == null) {
            editorFragment = new EditorFragment();
            editorFragment.setListener(this);
        }
        if (editorFragment.isAdded()) return;
        getSupportFragmentManager().beginTransaction()
            .replace(R.id.editorContainer, editorFragment, "editor")
            .commit();
    }

    private void switchToEditorTab(int index) {
        if (index < 0 || index >= openFiles.size()) return;

        String filePath = openFiles.get(index).getFilePath();
        currentEditorFile = filePath;

        ensureEditorFragment();
        OpenFile openFile = findOpenFile(filePath);
        if (openFile != null) {
            editorFragment.setContent(filePath, openFile.getContent());
        }
        if (openFiles.isEmpty()) {
            noEditorPlaceholder.setVisibility(View.VISIBLE);
        }
        saveSessionNow();
    }

    private void closeEditorTab(int index) {
        if (index < 0 || index >= openFiles.size()) return;

        OpenFile openFile = openFiles.get(index);
        String filePath = openFile.getFilePath();

        projectIndexer.removeFile(openFile.getFileName());

        openFiles.remove(index);
        openFilesByPath.remove(filePath);
        tabLayout.removeTabAt(index);

        if (!openFiles.isEmpty()) {
            int newIndex = Math.min(index, openFiles.size() - 1);
            tabLayout.selectTab(tabLayout.getTabAt(newIndex));
        } else {
            currentEditorFile = "";
            if (editorFragment != null) {
                editorFragment.setContent("", "");
            }
            noEditorPlaceholder.setVisibility(View.VISIBLE);
        }
        saveSessionNow();
    }

    private void updateTabModified(String filePath, boolean modified) {
        int index = indexOfOpenFile(filePath);
        if (index >= 0) {
            TabLayout.Tab tab = tabLayout.getTabAt(index);
            if (tab != null) {
                String name = openFiles.get(index).getFileName();
                tab.setText(modified ? "* " + name : name);
            }
        }
    }

    private int indexOfOpenFile(String filePath) {
        OpenFile openFile = openFilesByPath.get(filePath);
        return openFile != null ? openFiles.indexOf(openFile) : -1;
    }

    @Nullable
    private OpenFile findOpenFile(String filePath) {
        return filePath != null ? openFilesByPath.get(filePath) : null;
    }

    @SuppressLint("SetTextI18n")
    private void compileAndRun() {
        if (openFiles.isEmpty()) {
            Toast.makeText(this, "Open a file first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isCompiling) return;

        DependencyResolver.BuildSpec spec = loadBuildSpec();
        ProjectRecognizer.Detection detection = currentProject != null
            ? ProjectRecognizer.detect(currentProject.getRootDir(), currentEditorFile) : null;
        String language = detectRunLanguage(spec, detection);
        String entry = resolveEntryFile(spec, detection, language);

        switch (language) {
            case ProjectRecognizer.PYTHON:
                runScriptProgram(spec, entry, true);
                break;
            case ProjectRecognizer.JAVASCRIPT:
                runScriptProgram(spec, entry, false);
                break;
            case ProjectRecognizer.HTML:
            case ProjectRecognizer.CSS:
                runWebPreview(entry, language);
                break;
            default:
                compileAndRunJava(spec);
                break;
        }
    }

    private static String normalizeLanguageName(String raw) {
        switch (raw.trim().toLowerCase()) {
            case "java": return ProjectRecognizer.JAVA;
            case "python": case "py": return ProjectRecognizer.PYTHON;
            case "javascript": case "js": case "node": return ProjectRecognizer.JAVASCRIPT;
            case "html": return ProjectRecognizer.HTML;
            case "css": return ProjectRecognizer.CSS;
            default: return null;
        }
    }

    private String detectRunLanguage(DependencyResolver.BuildSpec spec,
                                     ProjectRecognizer.Detection detection) {
        if (spec != null && spec.language != null && !spec.language.isEmpty()) {
            String normalized = normalizeLanguageName(spec.language);
            if (normalized != null) return normalized;
        }
        if (!currentEditorFile.isEmpty()) {
            String activeLanguage = ProjectRecognizer.languageForExtension(
                new File(currentEditorFile).getName());
            if (activeLanguage != null) return activeLanguage;
        }
        if (detection != null && detection.primaryLanguage != null) {
            return detection.primaryLanguage;
        }
        return ProjectRecognizer.JAVA;
    }

    @Nullable
    private String resolveEntryFile(DependencyResolver.BuildSpec spec,
                                    ProjectRecognizer.Detection detection, String language) {
        if (spec != null && spec.entry != null && !spec.entry.isEmpty() && currentProject != null) {
            File candidate = new File(currentProject.getRootDir(), spec.entry);
            if (candidate.exists()) return candidate.getAbsolutePath();
        }
        if (!currentEditorFile.isEmpty() && language != null
            && language.equals(ProjectRecognizer.languageForExtension(
                new File(currentEditorFile).getName()))) {
            return currentEditorFile;
        }
        if (detection != null && detection.entryFile != null
            && language != null
            && language.equals(ProjectRecognizer.languageForExtension(
                new File(detection.entryFile).getName()))) {
            return detection.entryFile;
        }
        return null;
    }

    @Nullable
    private DependencyResolver.BuildSpec loadBuildSpec() {
        if (currentProject == null) return null;
        for (String name : new String[]{"larvbuild.json", "larv.json"}) {
            File f = new File(currentProject.getRootDir(), name);
            if (f.exists()) {
                try {
                    return DependencyResolver.readBuildSpec(f);
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static class RunStreams {
        final PipedInputStream programIn = new PipedInputStream(64 * 1024);
        final PipedOutputStream programOut = new PipedOutputStream();
        final PipedInputStream stdinIn = new PipedInputStream(64 * 1024);
        final PipedOutputStream stdinOut = new PipedOutputStream();
    }

    private RunStreams openRunTerminal(String statusLine) {
        RunStreams rs = new RunStreams();
        try {
            rs.programOut.connect(rs.programIn);
            rs.stdinOut.connect(rs.stdinIn);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create program pipes", e);
        }
        OutputFragment outputFragment = bottomPanelAdapter.getOutputFragment();
        runOnUiThread(() -> {
            statusText.setText(statusLine);
            outputFragment.clear();
            outputFragment.startProgram(rs.programIn, rs.stdinOut);
            bottomPanelAdapter.getErrorsFragment().setErrors(new ArrayList<>());
            bottomViewPager.setCurrentItem(0);
            bottomWindowVisible = true;
            bottomToolWindow.setVisibility(View.VISIBLE);
            bottomResizer.setVisibility(View.VISIBLE);
        });
        return rs;
    }

    private void runScriptProgram(DependencyResolver.BuildSpec spec, String entryPath,
                                  boolean python) {
        isCompiling = true;
        typeCheckHandler.removeCallbacksAndMessages(null);

        if (entryPath == null) {
            Toast.makeText(this, "No " + (python ? "Python" : "JavaScript")
                + " entry file found (main.py / index.js or the active tab)",
                Toast.LENGTH_LONG).show();
            isCompiling = false;
            return;
        }
        final File entryFile = new File(entryPath);
        if (!entryFile.exists()) {
            Toast.makeText(this, "Entry file not found: " + entryFile.getName(),
                Toast.LENGTH_SHORT).show();
            isCompiling = false;
            return;
        }

        final String source = readFileString(entryFile);
        RunStreams rs = openRunTerminal(python ? "Running Python..." : "Running JavaScript...");

        compilerExecutor.execute(() -> {
            List<File> preloads = new ArrayList<>();
            List<File> pyDirs = new ArrayList<>();
            if (spec != null && !spec.dependencies.isEmpty()) {
                writeTerm(rs.programOut, "Resolving " + spec.dependencies.size()
                    + " dependencies from larvbuild.json...\n");
                DependencyResolver.ResolveResult resolveResult = dependencyResolver.resolve(
                    spec.dependencies, spec.repositories,
                    msg -> writeTerm(rs.programOut, "  " + msg + "\n"));
                preloads.addAll(resolveResult.jars);
                pyDirs.addAll(resolveResult.pyPackageDirs);
                if (!resolveResult.success) {
                    writeTerm(rs.programOut, "Dependency error: " + resolveResult.error + "\n");
                    closeTermStreams(rs.programOut, rs.stdinOut);
                    runOnUiThread(() -> {
                        isCompiling = false;
                        statusText.setText("Dependency resolution failed");
                    });
                    return;
                }
                if (!preloads.isEmpty() || !pyDirs.isEmpty()) {
                    writeTerm(rs.programOut, "Dependencies ready (" + preloads.size()
                        + " packages)\n");
                }
            }

            writeTerm(rs.programOut, "\n");

            if (python) {
                PyRunner.RunResult result = pyRunner.run(source, pyDirs,
                    rs.programOut, rs.programOut);
                if (result.error != null && !"Python execution requires the native runtime module."
                    .equals(result.error)) {
                    writeTerm(rs.programOut, result.error + "\n");
                }
                writeTerm(rs.programOut, "\nProcess finished in " + result.durationMs + " ms\n");
                closeTermStreams(rs.programOut, rs.stdinOut);
                runOnUiThread(() -> {
                    isCompiling = false;
                    statusText.setText(result.success ? "Done" : "Finished with errors");
                });
            } else {
                JsRunner.RunResult result = jsRunner.run(source, entryFile.getName(),
                    preloads, rs.programOut, rs.programOut);
                if (result.error != null) {
                    writeTerm(rs.programOut, result.error + "\n");
                }
                writeTerm(rs.programOut, "\nProcess finished in " + result.durationMs + " ms\n");
                closeTermStreams(rs.programOut, rs.stdinOut);
                runOnUiThread(() -> {
                    isCompiling = false;
                    statusText.setText(result.success ? "Done" : "Finished with errors");
                });
            }
        });
    }

    private void runWebPreview(@Nullable String entryPath, String language) {
        isCompiling = true;
        String baseUrl = currentProject != null
            ? "file://" + currentProject.getRootDir().getAbsolutePath() + "/"
            : "about:blank";

        String html = null;
        String cssHref = null;
        if (entryPath != null && new File(entryPath).exists()) {
            String name = new File(entryPath).getName().toLowerCase();
            if (name.endsWith(".css")) {
                cssHref = relativeToRoot(entryPath);
            } else if (name.endsWith(".htm") || name.endsWith(".html")) {
                html = readFileString(new File(entryPath));
            }
        }
        if (html == null && !currentEditorFile.toLowerCase().endsWith(".css")) {
            String active = currentEditorFile.toLowerCase();
            if (active.endsWith(".html") || active.endsWith(".htm")) {
                html = readFileString(new File(currentEditorFile));
            }
        }
        if (html == null && cssHref == null) {
            File candidate = currentProject != null
                ? new File(currentProject.getRootDir(), "index.html") : null;
            if (candidate != null && candidate.exists()) {
                html = readFileString(candidate);
            }
        }
        if (html == null && cssHref == null) {
            Toast.makeText(this, "No HTML/CSS file found to preview", Toast.LENGTH_SHORT).show();
            isCompiling = false;
            return;
        }

        if (html == null) {
            html = "<!DOCTYPE html>\n<html>\n<head>\n"
                + "<link rel=\"stylesheet\" href=\"" + cssHref + "\">\n"
                + "</head>\n<body>\n"
                + "<h1>CSS Preview</h1>\n"
                + "<p>This page is rendered with your stylesheet applied.</p>\n"
                + "<button>Button sample</button>\n"
                + "</body>\n</html>\n";
        }

        final String finalHtml = html;
        final String finalBaseUrl = baseUrl;
        runOnUiThread(() -> {
            bottomPanelAdapter.getPreviewFragment().showHtml(finalHtml, finalBaseUrl);
            bottomWindowVisible = true;
            bottomToolWindow.setVisibility(View.VISIBLE);
            bottomResizer.setVisibility(View.VISIBLE);
            bottomViewPager.setCurrentItem(2);
            statusText.setText("Preview updated");
            isCompiling = false;
        });
    }

    @Nullable
    private String relativeToRoot(String path) {
        if (currentProject == null) return new File(path).getName();
        String root = currentProject.getRootDir().getAbsolutePath();
        return path.startsWith(root + File.separator)
            ? path.substring(root.length() + 1) : new File(path).getName();
    }

    private String readFileString(File file) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private void compileAndRunJava(DependencyResolver.BuildSpec buildSpec) {
        isCompiling = true;
        typeCheckHandler.removeCallbacksAndMessages(null);

        OutputFragment outputFragment = bottomPanelAdapter.getOutputFragment();

        RunStreams rs = new RunStreams();
        try {
            rs.programOut.connect(rs.programIn);
            rs.stdinOut.connect(rs.stdinIn);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create program pipes", e);
        }

        runOnUiThread(() -> {
            statusText.setText("Compiling...");
            outputFragment.clear();
            outputFragment.startProgram(rs.programIn, rs.stdinOut);
            bottomPanelAdapter.getErrorsFragment().setErrors(new ArrayList<>());
            bottomViewPager.setCurrentItem(0);
            bottomWindowVisible = true;
            bottomToolWindow.setVisibility(View.VISIBLE);
            bottomResizer.setVisibility(View.VISIBLE);
        });

        compilerExecutor.execute(() -> {
            List<File> dependencyJars = new ArrayList<>();
            if (buildSpec != null && !buildSpec.dependencies.isEmpty()) {
                writeTerm(rs.programOut, "Resolving " + buildSpec.dependencies.size()
                    + " dependencies from larvbuild.json...\n");
                DependencyResolver.ResolveResult resolveResult = dependencyResolver.resolve(
                    buildSpec.dependencies, buildSpec.repositories,
                    msg -> writeTerm(rs.programOut, "  " + msg + "\n"));
                dependencyJars.addAll(resolveResult.jars);
                if (!resolveResult.success) {
                    writeTerm(rs.programOut, "Dependency error: " + resolveResult.error + "\n");
                    runOnUiThread(() -> {
                        isCompiling = false;
                        statusText.setText("Dependency resolution failed");
                    });
                    closeTermStreams(rs.programOut, rs.stdinOut);
                    return;
                }
                writeTerm(rs.programOut, "Dependencies ready ("
                    + dependencyJars.size() + " jars)\n");
            }

            JavaCompiler.CompilationResult compileResult = javaCompiler.compile(openFiles,
                dependencyJars, prefs.getString("javaLevel", "16"));
            android.util.Log.d("MainActivity", "compile success=" + compileResult.isSuccess()
                + " diagnostics=" + compileResult.getDiagnostics().size());
            if (!compileResult.isSuccess() && compileResult.getRawOutput() != null) {
                android.util.Log.d("MainActivity", "RAW COMPILER OUTPUT:\n" + compileResult.getRawOutput());
            }

            if (!compileResult.isSuccess()) {
                if (compileResult.getRawOutput() != null && !compileResult.getRawOutput().isEmpty()) {
                    writeTerm(rs.programOut, compileResult.getRawOutput());
                }
                runOnUiThread(() -> {
                    bottomPanelAdapter.getErrorsFragment().setErrors(compileResult.getDiagnostics());
                    isCompiling = false;
                    statusText.setText("Compilation failed");
                    bottomViewPager.setCurrentItem(1);
                });
                closeTermStreams(rs.programOut, rs.stdinOut);
                return;
            }

            writeTerm(rs.programOut, "Compilation successful");

            Dexer.DexResult dexResult = dexer.dex(compileResult.getClassFiles(), dependencyJars, null);
            if (!dexResult.isSuccess()) {
                writeTerm(rs.programOut, "Dex error: " + dexResult.getError());
                runOnUiThread(() -> {
                    isCompiling = false;
                    statusText.setText("Dex error");
                });
                closeTermStreams(rs.programOut, rs.stdinOut);
                return;
            }

            String mainClass = findMainClass(openFiles);
            if (mainClass == null && buildSpec != null && buildSpec.mainClass != null
                && !buildSpec.mainClass.isEmpty()) {
                mainClass = buildSpec.mainClass;
            }
            if (mainClass == null) {
                writeTerm(rs.programOut, "Error: No main class found");
                runOnUiThread(() -> {
                    isCompiling = false;
                    statusText.setText("No main class");
                });
                closeTermStreams(rs.programOut, rs.stdinOut);
                return;
            }

            writeTerm(rs.programOut, "Dex successful, running " + mainClass + "...");

            JavaRunner.RunResult runResult = javaRunner.run(
                dexResult.getDexFile(), mainClass, new String[]{},
                rs.programOut, rs.programOut, rs.stdinIn);

            if (runResult.getError() != null) {
                writeTerm(rs.programOut, runResult.getError());
            }
            if (runResult.isSuccess()) {
                writeTerm(rs.programOut, "Program finished (exit code 0)");
            }

            closeTermStreams(rs.programOut, rs.stdinOut);

            runOnUiThread(() -> {
                isCompiling = false;
                statusText.setText(runResult.isSuccess() ? "Done" : "Program finished with error");
            });
        });
    }

    private void writeTerm(OutputStream out, String text) {
        if (out == null || text == null) return;
        try {
            out.write(text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private void closeTermStreams(OutputStream programOutputOut, OutputStream programInputOut) {
        try {
            programOutputOut.close();
        } catch (IOException ignored) {
        }
        try {
            programInputOut.close();
        } catch (IOException ignored) {
        }
    }

    private String findMainClass(List<OpenFile> files) {
        for (OpenFile f : files) {
            String content = f.getContent();
            if (content.contains("public static void main(String[] args)") ||
                content.contains("public static void main(String args[])")) {
                return f.getFileName().replace(".java", "");
            }
        }
        return null;
    }

    private void scheduleTypeCheck() {
        typeCheckHandler.removeCallbacksAndMessages(null);
        if (isCompiling) return;
        typeCheckHandler.postDelayed(syntaxCheckRunnable, 350);
        typeCheckHandler.postDelayed(typeCheckRunnable, 1400);
    }

    private void runSyntaxCheck() {
        String file = currentEditorFile;
        if (file.isEmpty() || editorFragment == null || isCompiling) return;
        OpenFile active = findOpenFile(file);
        if (active == null) return;
        compilerExecutor.execute(() -> {
            if (!javaCompiler.hasChanges(active)) return;
            List<Diagnostic> diagnostics = javaCompiler.syntaxCheck(active);
            String json = GSON.toJson(diagnostics);
            runOnUiThread(() -> {
                if (editorFragment == null) return;
                if (!currentEditorFile.equals(active.getFilePath())) return;
                editorFragment.showDiagnosticsJson(json);
            });
        });
    }

    private void runTypeCheck() {
        if (openFiles.isEmpty() || isCompiling) return;
        if (!typeCheckRunning.compareAndSet(false, true)) return;
        if (!javaCompiler.needsCheck(openFiles)) {
            typeCheckRunning.set(false);
            return;
        }
        compilerExecutor.execute(() -> {
            JavaCompiler.CompilationResult result = javaCompiler.typeCheck(openFiles);
            String json = GSON.toJson(result.getDiagnostics());
            runOnUiThread(() -> {
                if (openFiles.isEmpty()) return;
                bottomPanelAdapter.getErrorsFragment().setErrors(result.getDiagnostics());
                if (!currentEditorFile.isEmpty() && editorFragment != null) {
                    editorFragment.showDiagnosticsJson(json);
                }
            });
            typeCheckRunning.set(false);
        });
    }

    private String resolveTargetDirectory() {
        if (selectedDirectory != null && !selectedDirectory.isEmpty()) {
            File dir = new File(selectedDirectory);
            if (dir.exists() && dir.isDirectory()) {
                return dir.getAbsolutePath();
            }
        }
        return currentProject != null ? currentProject.getPath() : "";
    }

    private void showNewFileDialog() {
        String target = resolveTargetDirectory();
        if (target.isEmpty()) return;

        final String parentPath = target;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("New File");
        builder.setMessage("In: " + new File(parentPath).getName());

        EditText input = createIdeInput("File name (e.g., MyClass)");
        builder.setView(wrapDialogView(input));

        builder.setPositiveButton("Create", (dialog, which) -> {
            String fileName = input.getText().toString().trim();
            if (fileName.isEmpty()) {
                Toast.makeText(this, "File name cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            projectManager.createFile(parentPath, fileName,
                new ProjectManager.OnFileOperationCallback() {
                    @Override
                    public void onSuccess(File file) {
                        openFileInEditor(file);
                        projectManager.refreshFileTree();
                    }
                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show());
                    }
                });
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showNewFolderDialog() {
        String target = resolveTargetDirectory();
        if (target.isEmpty()) return;

        final String parentPath = target;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("New Folder");
        builder.setMessage("In: " + new File(parentPath).getName());

        EditText input = createIdeInput("Folder name");
        builder.setView(wrapDialogView(input));

        builder.setPositiveButton("Create", (dialog, which) -> {
            String folderName = input.getText().toString().trim();
            if (folderName.isEmpty()) {
                Toast.makeText(this, "Folder name cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            projectManager.createFolder(parentPath, folderName,
                new ProjectManager.OnFileOperationCallback() {
                    @Override
                    public void onSuccess(File file) {
                        selectedDirectory = file.getAbsolutePath();
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Folder created", Toast.LENGTH_SHORT).show();
                            statusText.setText("Folder created: " + file.getName());
                            projectManager.refreshFileTree();
                        });
                    }
                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show());
                    }
                });
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showFileContextMenu(FileNode node, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(node.getName()).setEnabled(false);

        if (node.getType() == FileNode.Type.DIRECTORY) {
            popup.getMenu().add("New File").setOnMenuItemClickListener(item -> {
                selectedDirectory = node.getPath();
                showNewFileDialog();
                return true;
            });
            popup.getMenu().add("New Folder").setOnMenuItemClickListener(item -> {
                selectedDirectory = node.getPath();
                showNewFolderDialog();
                return true;
            });
            popup.getMenu().add("Open").setOnMenuItemClickListener(item -> {
                selectedDirectory = node.getPath();
                fileTreeAdapter.toggleExpansion(node);
                return true;
            });
        } else {
            popup.getMenu().add("Open").setOnMenuItemClickListener(item -> {
                openFileInEditor(new File(node.getPath()));
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

    private void showTabCloseMenu(int index, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("Close").setOnMenuItemClickListener(item -> {
            closeEditorTab(index);
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

    private void closeOtherTabs(int keepIndex) {
        for (int i = openFiles.size() - 1; i >= 0; i--) {
            if (i != keepIndex) {
                closeEditorTab(i);
            }
        }
    }

    private void closeAllTabs() {
        for (int i = openFiles.size() - 1; i >= 0; i--) {
            closeEditorTab(i);
        }
    }

    private void showRenameDialog(FileNode node) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename");

        EditText input = createIdeInput(null);
        input.setText(node.getName());
        input.setSelection(input.getText().length());
        builder.setView(wrapDialogView(input));

        builder.setPositiveButton("Rename", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty() && !newName.equals(node.getName())) {
                projectManager.renameFile(new File(node.getPath()), newName,
                    new ProjectManager.OnFileOperationCallback() {
                        @Override
                        public void onSuccess(File file) {
                            projectManager.refreshFileTree();
                        }
                        @Override
                        public void onError(String error) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show());
                        }
                    });
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void confirmDelete(FileNode node) {
        new AlertDialog.Builder(this)
            .setTitle("Delete " + node.getName() + "?")
            .setMessage("This action cannot be undone.")
            .setPositiveButton("Delete", (dialog, which) -> {
                projectManager.deleteFile(new File(node.getPath()),
                    new ProjectManager.OnFileOperationCallback() {
                        @Override
                        public void onSuccess(File file) {
                            projectManager.refreshFileTree();
                            if (file.isDirectory() && selectedDirectory.startsWith(file.getAbsolutePath())) {
                                selectedDirectory = currentProject != null ? currentProject.getPath() : "";
                            }
                        }
                        @Override
                        public void onError(String error) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show());
                        }
                    });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showSettingsDialog() {
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, dp(8), pad, dp(8));

        android.widget.TextView fontLabel = new android.widget.TextView(this);
        fontLabel.setText("Editor font size");
        fontLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        fontLabel.setTextSize(12);
        root.addView(fontLabel);

        final android.widget.SeekBar fontSeek = new android.widget.SeekBar(this);
        final int savedFont = prefs.getInt("editorFontSize", 14);
        fontSeek.setMin(10);
        fontSeek.setMax(24);
        fontSeek.setProgress(savedFont);
        fontSeek.getProgressDrawable().setColorFilter(
            ContextCompat.getColor(this, R.color.ide_accent), android.graphics.PorterDuff.Mode.SRC_IN);
        fontSeek.getThumb().setColorFilter(
            ContextCompat.getColor(this, R.color.ide_accent), android.graphics.PorterDuff.Mode.SRC_IN);
        root.addView(fontSeek);

        final android.widget.TextView fontValue = new android.widget.TextView(this);
        fontValue.setText(savedFont + " pt");
        fontValue.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        fontValue.setTextSize(13);
        fontSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int value, boolean fromUser) {
                fontValue.setText(value + " pt");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
        });
        root.addView(fontValue);

        root.addView(settingSwitch("Word wrap", prefs.getBoolean("editorWordWrap", false),
            (buttonView, isChecked) -> prefs.edit().putBoolean("editorWordWrap", isChecked).apply()));
        root.addView(settingSwitch("Show line numbers", prefs.getBoolean("editorLineNumbers", true),
            (buttonView, isChecked) -> prefs.edit().putBoolean("editorLineNumbers", isChecked).apply()));
        root.addView(settingSwitch("Auto save", prefs.getBoolean("autosaveEnabled", true),
            (buttonView, isChecked) -> prefs.edit().putBoolean("autosaveEnabled", isChecked).apply()));

        android.widget.TextView tabLabel = new android.widget.TextView(this);
        tabLabel.setText("Tab size");
        tabLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tabLabel.setTextSize(12);
        tabLabel.setPadding(0, dp(12), 0, dp(4));
        root.addView(tabLabel);

        final String[] tabSizes = {"2", "4", "8"};
        final int savedTab = prefs.getInt("editorTabSize", 4);
        final android.widget.Spinner tabSpinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> tabAdapter = new android.widget.ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, tabSizes);
        tabAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        tabSpinner.setAdapter(tabAdapter);
        tabSpinner.setSelection(Math.max(0, java.util.Arrays.asList(tabSizes).indexOf(String.valueOf(savedTab))));
        root.addView(tabSpinner);

        android.widget.TextView levelLabel = new android.widget.TextView(this);
        levelLabel.setText("Java language level");
        levelLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        levelLabel.setTextSize(12);
        levelLabel.setPadding(0, dp(12), 0, dp(4));
        root.addView(levelLabel);

        final String[] levels = {"11", "16", "17"};
        final String savedLevel = prefs.getString("javaLevel", "16");
        final android.widget.Spinner levelSpinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> levelAdapter = new android.widget.ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, levels);
        levelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        levelSpinner.setAdapter(levelAdapter);
        levelSpinner.setSelection(Math.max(0, java.util.Arrays.asList(levels).indexOf(savedLevel)));
        root.addView(levelSpinner);

        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.addView(root);

        new AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(scroller)
            .setPositiveButton("Apply", (dialog, which) -> {
                prefs.edit()
                    .putInt("editorFontSize", fontSeek.getProgress())
                    .putInt("editorTabSize", Integer.parseInt((String) tabSpinner.getSelectedItem()))
                    .putString("javaLevel", (String) levelSpinner.getSelectedItem())
                    .apply();
                if (editorFragment != null) {
                    editorFragment.applyEditorSettings(
                        fontSeek.getProgress(),
                        Integer.parseInt((String) tabSpinner.getSelectedItem()),
                        prefs.getBoolean("editorLineNumbers", true),
                        prefs.getBoolean("editorWordWrap", false));
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private android.widget.LinearLayout settingSwitch(String label, boolean checked,
                                                      android.widget.CompoundButton.OnCheckedChangeListener listener) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(2));

        android.widget.TextView text = new android.widget.TextView(this);
        text.setText(label);
        text.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        text.setTextSize(14);
        text.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text);

        androidx.appcompat.widget.SwitchCompat sw = new androidx.appcompat.widget.SwitchCompat(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        sw.getTrackDrawable().setColorFilter(
            ContextCompat.getColor(this, checked ? R.color.ide_accent : R.color.input_stroke),
            android.graphics.PorterDuff.Mode.SRC_IN);
        row.addView(sw);
        return row;
    }

    private void showWelcomeStatus(boolean projectOpen) {
        statusText.setText(projectOpen ? "Ready" : "Ready");
    }

    private void showNewProjectDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("New Project");

        EditText input = createIdeInput("Project name");
        builder.setView(wrapDialogView(input));

        builder.setPositiveButton("Create", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                projectManager.createProject(name, new ProjectManager.OnProjectCreatedCallback() {
                    @Override
                    public void onCreated(Project project) {
                        projectManager.openProject(project);
                    }
                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show());
                    }
                });
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showOpenProjectDialog() {
        List<Project> projects = projectManager.getProjects();
        if (projects.isEmpty()) {
            Toast.makeText(this, "No projects found. Create one first.", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = new String[projects.size()];
        for (int i = 0; i < projects.size(); i++) {
            names[i] = projects.get(i).getName();
        }

        new AlertDialog.Builder(this)
            .setTitle("Open Project")
            .setItems(names, (dialog, which) -> projectManager.openProject(projects.get(which)))
            .show();
    }

    private void saveCurrentFile() {
        if (!currentEditorFile.isEmpty()) {
            OpenFile openFile = findOpenFile(currentEditorFile);
            if (openFile != null && openFile.isModified()) {
                projectManager.writeFile(new File(currentEditorFile), openFile.getContent(),
                    new ProjectManager.OnFileOperationCallback() {
                        @Override
                        public void onSuccess(File file) {
                            openFile.setModified(false);
                            updateTabModified(currentEditorFile, false);
                            statusText.setText("Saved: " + file.getName());
                        }
                        @Override
                        public void onError(String error) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show());
                        }
                    });
            } else {
                statusText.setText("Nothing to save");
            }
        }
    }

    private void saveAllFiles() {
        for (OpenFile openFile : openFiles) {
            if (openFile.isModified()) {
                String filePath = openFile.getFilePath();
                projectManager.writeFile(new File(filePath), openFile.getContent(),
                    new ProjectManager.OnFileOperationCallback() {
                        @Override
                        public void onSuccess(File file) {
                            openFile.setModified(false);
                            runOnUiThread(() -> updateTabModified(filePath, false));
                        }
                        @Override
                        public void onError(String error) {
                            Log.w(TAG, "Save failed for " + filePath + ": " + error);
                        }
                    });
            }
        }
        statusText.setText("All files saved");
    }

    private void autoSaveModifiedFiles() {
        for (OpenFile openFile : openFiles) {
            if (openFile.isModified()) {
                String filePath = openFile.getFilePath();
                projectManager.writeFile(new File(filePath), openFile.getContent(),
                    new ProjectManager.OnFileOperationCallback() {
                        @Override
                        public void onSuccess(File file) {
                            openFile.setModified(false);
                            runOnUiThread(() -> updateTabModified(filePath, false));
                        }
                        @Override
                        public void onError(String error) {
                            Log.w(TAG, "Auto-save failed for " + filePath + ": " + error);
                        }
                    });
            }
        }
    }

    private void saveAllModifiedFilesSync() {
        for (OpenFile openFile : openFiles) {
            if (openFile.isModified()) {
                File file = new File(openFile.getFilePath());
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                    fos.write(openFile.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    openFile.setModified(false);
                    updateTabModified(openFile.getFilePath(), false);
                } catch (IOException e) {
                    Log.w(TAG, "Auto-save failed for " + file.getAbsolutePath(), e);
                }
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        autosaveHandler.removeCallbacks(autosaveRunnable);
        saveAllModifiedFilesSync();
        saveSessionNow();
    }

    private void updateWindowTitle() {
        String title = getString(R.string.app_name);
        if (currentProject != null) {
            title += " - " + currentProject.getName();
        }
        setTitle(title);
    }

    private void showMenuBarPopup(View anchor, int menuRes, java.util.function.BiPredicate<Integer, Integer> itemHandler, java.util.function.Consumer<android.view.Menu> preparer) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
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

    private boolean onMenuBarItemSelected(int itemId, int groupId) {
        if (itemId == R.id.menu_new_project) {
            showNewProjectDialog();
            return true;
        } else if (itemId == R.id.menu_open_project) {
            showOpenProjectDialog();
            return true;
        } else if (itemId == R.id.menu_new_file) {
            if (currentProject == null) {
                Toast.makeText(this, "Open a project first", Toast.LENGTH_SHORT).show();
            } else {
                showNewFileDialog();
            }
            return true;
        } else if (itemId == R.id.menu_new_folder) {
            if (currentProject == null) {
                Toast.makeText(this, "Open a project first", Toast.LENGTH_SHORT).show();
            } else {
                showNewFolderDialog();
            }
            return true;
        } else if (itemId == R.id.menu_save) {
            saveCurrentFile();
            return true;
        } else if (itemId == R.id.menu_save_all) {
            saveAllFiles();
            return true;
        } else if (itemId == R.id.menu_close_project) {
            projectManager.closeProject();
            return true;
        } else if (itemId == R.id.menu_undo) {
            runOnCurrentEditor(f -> f.execAction("undo"));
            return true;
        } else if (itemId == R.id.menu_redo) {
            runOnCurrentEditor(f -> f.execAction("redo"));
            return true;
        } else if (itemId == R.id.menu_select_all) {
            runOnCurrentEditor(f -> f.execAction("selectAll"));
            return true;
        } else if (itemId == R.id.menu_find) {
            runOnCurrentEditor(f -> f.execAction("find"));
            return true;
        } else if (itemId == R.id.menu_find_next) {
            runOnCurrentEditor(f -> f.execAction("findNext"));
            return true;
        } else if (itemId == R.id.menu_find_previous) {
            runOnCurrentEditor(f -> f.execAction("findPrevious"));
            return true;
        } else if (itemId == R.id.menu_toggle_project) {
            toggleLeftWindow();
            return true;
        } else if (itemId == R.id.menu_toggle_terminal) {
            toggleBottomWindow();
            return true;
        } else if (itemId == R.id.menu_expand_editor) {
            toggleMaximizeEditor();
            return true;
        } else if (itemId == R.id.menu_run) {
            compileAndRun();
            return true;
        } else if (itemId == R.id.menu_compile) {
            compileAndRun();
            return true;
        } else if (itemId == R.id.menu_clean) {
            clearBuildOutput();
            return true;
        } else if (itemId == R.id.menu_settings) {
            showSettingsDialog();
            return true;
        } else if (itemId == R.id.menu_about) {
            showAboutDialog();
            return true;
        }
        return false;
    }

    private void runOnCurrentEditor(java.util.function.Consumer<EditorFragment> action) {
        if (!currentEditorFile.isEmpty() && editorFragment != null) {
            action.accept(editorFragment);
        }
    }

    private void syncViewMenuState(android.view.Menu menu) {
        android.view.MenuItem projectItem = menu.findItem(R.id.menu_toggle_project);
        if (projectItem != null) {
            projectItem.setChecked(leftWindowVisible);
        }
        android.view.MenuItem terminalItem = menu.findItem(R.id.menu_toggle_terminal);
        if (terminalItem != null) {
            terminalItem.setChecked(bottomWindowVisible);
        }
        android.view.MenuItem expandItem = menu.findItem(R.id.menu_expand_editor);
        if (expandItem != null) {
            expandItem.setChecked(editorMaximized);
        }
    }

    private void clearBuildOutput() {
        javaCompiler.clearOutputDirectory();
        statusText.setText("Build output cleared");
    }

    @NonNull
    private EditText createIdeInput(String hint) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        if (hint != null) {
            input.setHint(hint);
        }
        input.setTextSize(14);
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_disabled));
        input.setBackground(ContextCompat.getDrawable(this, R.drawable.edittext_ide));
        int p = dp(12);
        input.setPadding(p, dp(10), p, dp(10));
        return input;
    }

    @NonNull
    private View wrapDialogView(View view) {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(4), dp(24), dp(8));
        box.addView(view, new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
            .setTitle("About LarvIDE")
            .setMessage("LarvIDE - Lightweight Java IDE for Android\nVersion 1.0\nBuilt with ECJ, R8, Monaco Editor")
            .setPositiveButton("OK", null)
            .show();
    }

    private void toggleLeftWindow() {
        leftWindowVisible = !leftWindowVisible;
        leftToolWindowContent.setVisibility(leftWindowVisible ? View.VISIBLE : View.GONE);
        leftResizer.setVisibility(leftWindowVisible ? View.VISIBLE : View.GONE);
    }

    private void closeLeftWindow() {
        leftWindowVisible = false;
        leftToolWindowContent.setVisibility(View.GONE);
        leftResizer.setVisibility(View.GONE);
    }

    private void toggleBottomWindow() {
        bottomWindowVisible = !bottomWindowVisible;
        bottomToolWindow.setVisibility(bottomWindowVisible ? View.VISIBLE : View.GONE);
        bottomResizer.setVisibility(bottomWindowVisible ? View.VISIBLE : View.GONE);
    }

    private void closeBottomWindow() {
        bottomWindowVisible = false;
        bottomToolWindow.setVisibility(View.GONE);
        bottomResizer.setVisibility(View.GONE);
    }

    private void showWelcome(boolean show) {
        welcomeView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            noEditorPlaceholder.setVisibility(View.GONE);
        } else if (openFiles.isEmpty()) {
            noEditorPlaceholder.setVisibility(View.VISIBLE);
        }
    }

    private void toggleMaximizeEditor() {
        editorMaximized = !editorMaximized;
        if (editorMaximized) {
            leftToolWindowContent.setVisibility(View.GONE);
            leftResizer.setVisibility(View.GONE);
            bottomToolWindow.setVisibility(View.GONE);
            bottomResizer.setVisibility(View.GONE);
            statusText.setText("Editor maximized");
        } else {
            leftToolWindowContent.setVisibility(leftWindowVisible ? View.VISIBLE : View.GONE);
            leftResizer.setVisibility(leftWindowVisible ? View.VISIBLE : View.GONE);
            bottomToolWindow.setVisibility(bottomWindowVisible ? View.VISIBLE : View.GONE);
            bottomResizer.setVisibility(bottomWindowVisible ? View.VISIBLE : View.GONE);
            statusText.setText("Layout restored");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        compilerExecutor.shutdown();
        indexerExecutor.shutdown();
        typeCheckHandler.removeCallbacksAndMessages(null);
        if (projectIndexer != null) {
            projectIndexer.clear();
        }
    }
}
