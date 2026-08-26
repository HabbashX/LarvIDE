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
import com.larv.ide.build.LarvBuildParser;
import com.larv.ide.compiler.Dexer;
import com.larv.ide.compiler.JavaCompiler;
import com.larv.ide.compiler.JavaRunner;
import com.larv.ide.compiler.JavascriptRunner;
import com.larv.ide.compiler.PythonRunner;
import com.larv.ide.completion.CompletionItem;
import com.larv.ide.completion.ProjectIndexer;
import com.larv.ide.model.FileNode;
import com.larv.ide.model.Diagnostic;
import com.larv.ide.model.OpenFile;
import com.larv.ide.model.Project;
import com.larv.ide.project.ProjectManager;
import com.larv.ide.project.ProjectRecognizer;
import com.larv.ide.run.RunDispatcher;
import com.larv.ide.run.backend.termux.TermuxCommandBackend;
import com.larv.ide.session.SessionManager;
import com.larv.ide.ui.dialog.SettingsDialog;
import com.larv.ide.ui.adapter.BottomPanelAdapter;
import com.larv.ide.ui.adapter.FileTreeAdapter;
import com.larv.ide.ui.fragment.EditorFragment;
import com.larv.ide.ui.fragment.OutputFragment;


import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
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
    private JavascriptRunner javascriptRunner;
    private PythonRunner pythonRunner;
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
    private RunDispatcher runDispatcher;
    private TermuxCommandBackend termuxBackend;
    private SessionManager sessionManager;
    private com.larv.ide.run.backend.termux.TermuxSetupWizard termuxWizard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        applySelectedTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initServices();
        setupListeners();
        checkPermissions();

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
        bottomViewPager.setOffscreenPageLimit(4);

        new TabLayoutMediator(bottomTabLayout, bottomViewPager, (tab, position) -> {
            String title;
            switch (position) {
                case 1: title = getString(R.string.errors_title); break;
                case 2: title = "Preview"; break;
                case 3: title = "Terminal"; break;
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
        javascriptRunner = new JavascriptRunner();
        termuxBackend = new TermuxCommandBackend(getApplicationContext());
        runDispatcher = new RunDispatcher(new RunHost(), termuxBackend);
        sessionManager = new SessionManager(new SessionManager.Host() {
            @Override public Project currentProject() { return currentProject; }
            @Override public java.util.List<OpenFile> openFiles() { return openFiles; }
            @Override public java.util.Map<String, OpenFile> filesByPath() { return openFilesByPath; }
            @Override public String activeFile() { return currentEditorFile; }
            @Override public EditorFragment editorFragment() { return editorFragment; }
            @Override public com.google.android.material.tabs.TabLayout tabLayout() { return tabLayout; }
            @Override public void ensureEditorFragment() { MainActivity.this.ensureEditorFragment(); }
            @Override public void hideEditorPlaceholders() {
                noEditorPlaceholder.setVisibility(View.GONE);
                welcomeView.setVisibility(View.GONE);
            }
            @Override public void switchToTab(int index) { switchToEditorTab(index); }
            @Override public ProjectManager projectManager() { return projectManager; }
            @Override public ProjectIndexer indexer() { return projectIndexer; }
            @Override public java.util.concurrent.ExecutorService executor() { return indexerExecutor; }
            @Override public void runOnUiThread(Runnable action) { MainActivity.this.runOnUiThread(action); }
        });
        pythonRunner = new PythonRunner(getApplicationContext());
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
        if (requestCode == com.larv.ide.run.backend.termux.TermuxSetupWizard.PERMISSION_REQUEST_CODE) {
            if (termuxWizard != null) {
                termuxWizard.onPermissionResult();
            }
            return;
        }
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
        closeAllOpenTabs();
        runOnUiThread(() -> {
            bottomToolWindow.setVisibility(editorMaximized ? View.GONE : View.VISIBLE);
            bottomWindowVisible = true;
            updateWindowTitle();
            showWelcomeStatus(true);
            showWelcome(false);
        });
        sessionManager.restore(project);
        indexerExecutor.execute(() -> {
            ProjectRecognizer.Detection detection = ProjectRecognizer.detect(project.getRootDir(), "");
            if (!detection.languages.isEmpty()) {
                String joined = String.join(", ", detection.languages);
                runOnUiThread(() -> statusText.setText("Project: " + joined));
            }
        });
    }

    @Override
    public void onProjectClosed() {
        currentProject = null;
        sessionManager.reset();
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
        sessionManager.scheduleSave();
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

    private void applySelectedTheme() {
        switch (prefs.getString("appAccent", "blue")) {
            case "purple": setTheme(R.style.Theme_LarvIDE_AccentPurple); break;
            case "green": setTheme(R.style.Theme_LarvIDE_AccentGreen); break;
            case "orange": setTheme(R.style.Theme_LarvIDE_AccentOrange); break;
            case "pink": setTheme(R.style.Theme_LarvIDE_AccentPink); break;
            case "cyan": setTheme(R.style.Theme_LarvIDE_AccentCyan); break;
            case "blue":
            default: setTheme(R.style.Theme_LarvIDE_AccentBlue); break;
        }
    }

    private int themeColor(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    private int themeColorOrFallback(int attr, int fallbackRes) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(attr, tv, true)) {
            return tv.data;
        }
        return ContextCompat.getColor(this, fallbackRes);
    }

    @Override
    public void onEditorReady() {
        if (editorFragment != null) {
            if (sessionManager.hasPendingCursorPositions()) {
                editorFragment.setCursorPositions(sessionManager.consumePendingCursorPositions());
            }
            editorFragment.applyEditorSettings(
                prefs.getInt("editorFontSize", 14),
                prefs.getInt("editorTabSize", 4),
                prefs.getBoolean("editorLineNumbers", true),
                prefs.getBoolean("editorWordWrap", false),
                prefs.getBoolean("editorMinimap", false),
                prefs.getBoolean("editorIndentGuides", true),
                prefs.getBoolean("editorHighlightLine", true),
                prefs.getString("editorFontFamily", "jetbrains"));
            editorFragment.applyEditorTheme(prefs.getString("editorTheme", "islands-dark"));
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
        sessionManager.saveNow();
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
        sessionManager.saveNow();
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

    private void compileAndRun() {
        runDispatcher.dispatch();
    }

    private class RunHost implements RunDispatcher.Host {
        @Override public boolean isBusy() { return isCompiling; }
        @Override public void setBusy(boolean busy) { isCompiling = busy; }
        @Override public Project currentProject() { return currentProject; }
        @Override public String activeFilePath() { return currentEditorFile; }
        @Override public List<OpenFile> openFiles() { return openFiles; }
        @Override public JavaCompiler javaCompiler() { return javaCompiler; }
        @Override public Dexer dexer() { return dexer; }
        @Override public LarvBuildParser.BuildSpec loadBuildSpec() {
            if (currentProject == null) return null;
            for (String name : new String[]{"larvbuild.json", "larv.json"}) {
                java.io.File f = new java.io.File(currentProject.getRootDir(), name);
                if (f.exists()) {
                    try {
                        return LarvBuildParser.readBuildSpec(f);
                    } catch (Exception ignored) { }
                }
            }
            return null;
        }
        @Override public JavascriptRunner javascriptRunner() { return javascriptRunner; }
        @Override public PythonRunner pythonRunner() { return pythonRunner; }
        @Override public JavaRunner javaRunner() { return javaRunner; }
        @Override public java.util.concurrent.ExecutorService executor() { return compilerExecutor; }
        @Override public android.os.Handler typeCheckHandler() { return typeCheckHandler; }
        @Override public RunDispatcher.RunStreams openRunTerminal(String statusLine) {
            return MainActivity.this.openRunTerminal(statusLine);
        }
        @Override public void writeTerm(OutputStream out, String text) {
            MainActivity.this.writeTerm(out, text);
        }
        @Override public void closeTermStreams(OutputStream programOut, OutputStream stdinOut) {
            MainActivity.this.closeTermStreams(programOut, stdinOut);
        }
        @Override public void setStatus(String text) {
            runOnUiThread(() -> statusText.setText(text));
        }
        @Override public void showErrors(java.util.List<Diagnostic> diagnostics) {
            runOnUiThread(() ->
                bottomPanelAdapter.getErrorsFragment().setErrors(new ArrayList<>(diagnostics)));
        }
        @Override public void showErrorTab() {
            runOnUiThread(() -> bottomViewPager.setCurrentItem(1));
        }
        @Override public void showPreview(String html, String baseUrl) {
            runOnUiThread(() -> {
                bottomPanelAdapter.getPreviewFragment().showHtml(html, baseUrl);
                bottomWindowVisible = true;
                bottomToolWindow.setVisibility(View.VISIBLE);
                bottomResizer.setVisibility(View.VISIBLE);
                bottomViewPager.setCurrentItem(2);
                statusText.setText("Preview updated");
            });
        }
        @Override public android.content.SharedPreferences prefs() { return prefs; }
        @Override public String findMainClass(List<OpenFile> files) {
            return MainActivity.this.findMainClass(files);
        }
        @Override public void executeTermux(com.larv.ide.run.backend.ExecRequest request) throws com.larv.ide.run.backend.BackendUnavailableException {
            termuxBackend.execute(request);
        }
        @Override public void openTermuxWizard() {
            runOnUiThread(() -> {
                termuxWizard = new com.larv.ide.run.backend.termux.TermuxSetupWizard(MainActivity.this);
                termuxWizard.show();
            });
        }
        @Override public void toast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message,
                Toast.LENGTH_SHORT).show());
        }
    }

    private RunDispatcher.RunStreams openRunTerminal(String statusLine) {
        RunDispatcher.RunStreams rs = new RunDispatcher.RunStreams();
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

    private void writeTerm(OutputStream out, String text) {        if (out == null || text == null) return;
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
        SettingsDialog.show(this, new SettingsDialog.Callbacks() {
            @Override public android.content.SharedPreferences prefs() { return prefs; }
            @Override public int accentColor() {
                return themeColorOrFallback(
                    com.google.android.material.R.attr.colorPrimary, R.color.ide_accent);
            }
            @Override public void onAccentChanged(String newAccent) {
                recreate();
            }
            @Override public void onEditorSettingsApplied(String themeId, String fontFamily,
                                                          int fontSize, int tabSize) {
                if (editorFragment != null) {
                    editorFragment.applyEditorSettings(fontSize, tabSize,
                        prefs.getBoolean("editorLineNumbers", true),
                        prefs.getBoolean("editorWordWrap", false),
                        prefs.getBoolean("editorMinimap", false),
                        prefs.getBoolean("editorIndentGuides", true),
                        prefs.getBoolean("editorHighlightLine", true),
                        fontFamily);
                    editorFragment.applyEditorTheme(themeId);
                }
            }
        });
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
        sessionManager.saveNow();
    }

    private void openTermuxShell() {
        File workdir = currentProject != null ? currentProject.getRootDir() : getFilesDir();
        try {
            termuxBackend.execute(new com.larv.ide.run.backend.ExecRequest(
                java.util.Arrays.asList("bash"), workdir.getAbsolutePath(), true));
            Toast.makeText(this, "Opened a shell in Termux at: " + workdir.getName(),
                Toast.LENGTH_SHORT).show();
        } catch (Exception ex) {
            Toast.makeText(this, "Termux not ready — opening setup wizard", Toast.LENGTH_SHORT).show();
            showTermuxWizard();
        }
    }

    private void showTermuxWizard() {
        termuxWizard = new com.larv.ide.run.backend.termux.TermuxSetupWizard(this);
        termuxWizard.show();
    }

    private void openEmbeddedTerminal() {
        File workdir = currentProject != null
            ? currentProject.getRootDir() : getFilesDir();
        bottomPanelAdapter.getTerminalFragment().openSession(workdir);
        bottomViewPager.setCurrentItem(3);
        bottomWindowVisible = true;
        bottomToolWindow.setVisibility(View.VISIBLE);
        bottomResizer.setVisibility(View.VISIBLE);
    }

    private void closeAllOpenTabs() {
        runOnUiThread(() -> {
            openFiles.clear();
            openFilesByPath.clear();
            tabLayout.removeAllTabs();
            projectIndexer.clear();
            javaCompiler.resetCheckState();
            currentEditorFile = "";
            if (editorFragment != null && editorFragment.isAdded()) {
                editorFragment.setContent("", "");
            }
            noEditorPlaceholder.setVisibility(View.VISIBLE);
        });
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
        } else if (itemId == R.id.menu_open_terminal) {
            openEmbeddedTerminal();
            return true;
        } else if (itemId == R.id.menu_open_termux_shell) {
            openTermuxShell();
            return true;
        } else if (itemId == R.id.menu_languages) {
            com.larv.ide.ui.dialog.LanguagesDialog.show(this, termuxBackend,
                currentProject != null ? currentProject.getRootDir() : getFilesDir(),
                (pkg) -> {
                    try {
                        termuxBackend.execute(new com.larv.ide.run.backend.ExecRequest(
                            java.util.Arrays.asList("pkg", "install", "-y", pkg), null, true));
                        Toast.makeText(this, "Installing " + pkg + " — check the Termux window",
                            Toast.LENGTH_LONG).show();
                    } catch (Exception ex) {
                        Toast.makeText(this, "Termux not ready: open Build ▸ Run with Termux first",
                            Toast.LENGTH_LONG).show();
                    }
                });
            return true;
        } else if (itemId == R.id.menu_run_termux) {
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
