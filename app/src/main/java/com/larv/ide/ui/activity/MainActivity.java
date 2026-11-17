package com.larv.ide.ui.activity;

import com.larv.ide.R;

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
import android.util.Log;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import com.larv.ide.infrastructure.completion.ProjectIndexer;
import com.larv.ide.infrastructure.model.FileNode;

import com.larv.ide.infrastructure.model.OpenFile;
import com.larv.ide.infrastructure.model.Project;
import com.larv.ide.infrastructure.project.ProjectManager;
import com.larv.ide.infrastructure.project.ProjectRecognizer;
import com.larv.ide.infrastructure.run.RunDispatcher;
import com.larv.ide.ui.session.SessionManager;

import com.larv.ide.ui.adapter.BottomPanelAdapter;
import com.larv.ide.ui.adapter.FileTreeAdapter;
import com.larv.ide.ui.fragment.EditorFragment;


import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class MainActivity extends AppCompatActivity
        implements ProjectManager.OnProjectChangeListener,
        FileTreeAdapter.OnFileClickListener,
        EditorFragment.EditorListener {

    static final String TAG = "MainActivity";
    private static final int REQUEST_STORAGE_PERMISSION = 1001;
    private static final String PREFS_NAME = "larv_ide";
    private static final String PREF_LEFT_PANEL_WIDTH_DP = "left_panel_width_dp";
    private static final int DEFAULT_LEFT_PANEL_WIDTH_DP = 190;
    private static final String PREF_LAST_PROJECT = "last_project_path";

    SharedPreferences prefs;

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
    View noEditorPlaceholder;
    View welcomeView;
    TabLayout tabLayout;
    private ImageButton newTabButton;
    private ImageButton btnSplitEditor;
    ImageButton btnRun;
    private ImageButton btnCloseProjectWindow;
    private ImageButton btnCloseBottomWindow;

    FrameLayout bottomToolWindow;
    private TabLayout bottomTabLayout;
    ViewPager2 bottomViewPager;
    BottomPanelAdapter bottomPanelAdapter;

    android.widget.TextView statusText;
    private android.widget.TextView statusPosition;

    private View leftResizer;
    View bottomResizer;
    private int leftPanelWidthDp = 190;
    private float leftResizeStartX;
    private float leftResizeStartWidth;
    private long leftResizeDownTime;

    private final Handler autosaveHandler = new Handler(Looper.getMainLooper());
    private final Runnable autosaveRunnable = this::autoSaveModifiedFiles;


    androidx.recyclerview.widget.RecyclerView fileTreeRecyclerView;
    FileTreeAdapter fileTreeAdapter;
    private View projectSearchBar;
    private android.widget.EditText projectSearchInput;

    EditorFragment editorFragment;
    String currentEditorFile = "";
    private int lastStatusLine = -1;
    private int lastStatusColumn = -1;

    ProjectManager projectManager;
    ProjectIndexer projectIndexer;
    private com.larv.ide.infrastructure.performance.CacheManager cacheManager;
    private com.larv.ide.infrastructure.analysis.CompletionManager completionManager;
    private com.larv.ide.infrastructure.analysis.InspectionManager inspectionManager;
    private com.larv.ide.infrastructure.analysis.LanguageServerManager lspManager;
    private final List<com.larv.ide.infrastructure.model.Diagnostic> lspDiagnostics =
        new ArrayList<>();
    private final List<com.larv.ide.infrastructure.model.Diagnostic> compilerDiagnostics =
        new ArrayList<>();
    private final List<com.larv.ide.infrastructure.model.Diagnostic> inspectionDiagnostics =
        new ArrayList<>();
    final com.larv.ide.infrastructure.concurrent.TaskScheduler taskScheduler =
        new com.larv.ide.infrastructure.concurrent.TaskScheduler(
            new com.larv.ide.infrastructure.concurrent.IOExecutor(1),
            new com.larv.ide.infrastructure.concurrent.CPUExecutor(1),
            new com.larv.ide.infrastructure.concurrent.CompilationExecutor());
    final com.larv.ide.infrastructure.concurrent.CompilationExecutor compilerExecutor =
        taskScheduler.compilation();
    final com.larv.ide.infrastructure.concurrent.CPUExecutor indexerExecutor =
        taskScheduler.cpu();
    Project currentProject;
    final List<OpenFile> openFiles = new ArrayList<>();
    final Map<String, OpenFile> openFilesByPath = new HashMap<>();
    /** Currently running embedded program, if any - killed by the Stop button. */
    String selectedDirectory = "";
    boolean leftWindowVisible = true;
    boolean bottomWindowVisible = true;
    boolean editorMaximized = false;



    RunController runController;
    ProjectActions projectActions;
    EditorTabsController editorTabs;
    MainMenuController menuController;
    FileTreeDragController fileTreeDrag;
    com.larv.ide.infrastructure.run.backend.embedded.EmbeddedLinuxBackend embeddedBackend;
    SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        applySelectedTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fileTreeDrag = new FileTreeDragController(this);
        initViews();
        initServices();
        projectActions = new ProjectActions(this);
        editorTabs = new EditorTabsController(this);
        menuController = new MainMenuController(this);
        projectActions.registerProjectCreator();
        setupListeners();
        checkPermissions();

        handleIntent(getIntent());
        restoreLastProject();
        showWelcome(currentProject == null);
        enforceRuntimeGate();
    }

    /** First-launch onboarding: languages and toolchains. Skippable. */
    private void maybeShowSetupWizard() {
        if (prefs == null) return;
        if (!prefs.getBoolean(
                com.larv.ide.infrastructure.setup.SetupConfig.PREF_SETUP_COMPLETE, false)) {
            startActivity(new Intent(this,
                com.larv.ide.ui.setup.SetupWizardActivity.class));
        }
    }

    private boolean runtimeGateShowing = false;
    /** True when the user dismissed this gate; suppresses the follow-up wizard. */
    private boolean runtimeGateSkipped = false;

    /**
     * The Linux runtime is MANDATORY: LarvIDE has no built-in compilers, so
     * nothing can execute without it. Every launch with a missing runtime
     * blocks on this gate - Download, or Exit. The only exception is non-arm64
     * devices, where the runtime can never install (limited mode instead).
     */
    @SuppressLint("SetTextI18n")
    private void enforceRuntimeGate() {
        if (runtimeGateShowing) return;
        // A prefix without proot's dependencies only needs a small repair
        // instead of the full bootstrap download.
        boolean repair = com.larv.ide.infrastructure.run.backend.embedded.EmbeddedRuntime
            .bashFile(this).isFile();
        // "Later" only opts out of the initial download; a half-installed
        // runtime (the prefix is there but proot cannot launch) is broken and
        // must be repaired, otherwise every command fails with "Permission denied".
        if (prefs != null && prefs.getBoolean("runtimeGateDismissed", false) && !repair) return;
        if (com.larv.ide.infrastructure.run.backend.embedded.EmbeddedRuntime.isEmbeddedReady(this)) {
            maybeShowSetupWizard();
            return;
        }
        if (!com.larv.ide.infrastructure.run.backend.embedded.EmbeddedRuntime.isArm64()) {
            new AlertDialog.Builder(this)
                .setTitle("Device not supported")
                .setMessage("The Linux runtime needs arm64 - this device cannot install it. "
                    + "You can still browse and edit code, but Run is disabled.")
                .setPositiveButton("Continue in limited mode", null)
                .setCancelable(true)
                .show();
            return;
        }
        runtimeGateShowing = true;

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, dp(8), pad, dp(4));

        android.widget.TextView msg = new android.widget.TextView(this);
        msg.setText(repair
            ? "Finishing the Linux Environment setup (a few MB, one-time).\n\n"
                + "This repairs the proot binary and its libraries so commands and "
                + "toolchain installs stop failing with \"Permission denied\"."
            : "LarvIDE cannot run any code without the Linux Environment "
                + "(~100-150 MB, one-time download, WiFi recommended).\n\n"
                + "Download it now to unlock Java, C/C++, Python and Node.js.");
        msg.setTextSize(14);
        msg.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        root.addView(msg);

        android.widget.ProgressBar progress = new android.widget.ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        android.widget.TextView status = new android.widget.TextView(this);
        status.setTextSize(12);
        status.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        status.setPadding(0, dp(6), 0, 0);
        root.addView(status);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(repair ? "Repairing Linux Environment…" : "Downloading Linux Environment…")
            .setView(wrapDialogView(root))
            // A repair cannot be skipped, but a first download can.
            .setCancelable(!repair)
            .create();

        // Button bar (shown only on error — download starts automatically).
        android.widget.LinearLayout buttons = new android.widget.LinearLayout(this);
        buttons.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(12), 0, 0);

        android.widget.Button downloadBtn = new android.widget.Button(this, null, 0);
        downloadBtn.setText("Retry");
        downloadBtn.setVisibility(View.GONE);
        downloadBtn.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(downloadBtn);

        android.widget.Button laterBtn = new android.widget.Button(this, null, 0);
        laterBtn.setText("Later (limited mode)");
        laterBtn.setVisibility(View.GONE);
        laterBtn.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(laterBtn);

        android.widget.Button exitBtn = new android.widget.Button(this, null, 0);
        exitBtn.setText("Exit app");
        exitBtn.setVisibility(View.GONE);
        exitBtn.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(exitBtn);
        root.addView(buttons);

        // Auto-start download on first launch — no tap required. Show first so
        // progress/error callbacks always have a live dialog to talk to.
        dialog.show();
        postDownload(progress, status, downloadBtn, laterBtn, exitBtn, dialog, repair);
    }

    /** Auto-start prefix download so the user never has to tap a button. */
    private void postDownload(android.widget.ProgressBar progress,
                              android.widget.TextView status,
                              android.widget.Button downloadBtn,
                              android.widget.Button laterBtn,
                              android.widget.Button exitBtn,
                              AlertDialog dialog,
                              boolean repair) {
        boolean canSkip = !repair;
        runtimeGateSkipped = false;

        // The download keeps running inside the app process, so the user is
        // never trapped in this dialog: "Skip for now", Back and Exit all work
        // while it is in flight. Only a repair (a prefix that exists but is
        // broken) has to run to completion.
        downloadBtn.setVisibility(View.GONE);
        downloadBtn.setEnabled(false);
        laterBtn.setText("Skip for now");
        laterBtn.setVisibility(canSkip ? View.VISIBLE : View.GONE);
        laterBtn.setEnabled(canSkip);
        exitBtn.setVisibility(View.VISIBLE);
        exitBtn.setEnabled(true);

        laterBtn.setOnClickListener(v -> skipRuntimeGate(dialog));
        exitBtn.setOnClickListener(v -> finish());
        downloadBtn.setOnClickListener(v -> startEmbeddedInstall(
            progress, status, downloadBtn, dialog));

        if (canSkip) {
            // Back / outside-tap behaves exactly like "Skip for now".
            dialog.setOnCancelListener(d -> skipRuntimeGate(null));
        }

        startEmbeddedInstall(progress, status, downloadBtn, dialog);
    }

    /** Close the gate without waiting; an in-flight download may still finish. */
    private void skipRuntimeGate(AlertDialog dialog) {
        runtimeGateSkipped = true;
        runtimeGateShowing = false;
        if (prefs != null) {
            prefs.edit().putBoolean("runtimeGateDismissed", true).apply();
        }
        Toast.makeText(MainActivity.this,
            "Limited mode: Run stays disabled until the Linux Environment is installed.",
            Toast.LENGTH_LONG).show();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void startEmbeddedInstall(android.widget.ProgressBar progress,
                                      android.widget.TextView status,
                                      android.widget.Button downloadBtn,
                                      AlertDialog dialog) {
        downloadBtn.setEnabled(false);
        downloadBtn.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        status.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        status.setText("Downloading Linux Environment…");
        embeddedBackend.installer().installAsync(
            prefs.getString("embeddedBootstrapUrl", null),
            new com.larv.ide.infrastructure.run.backend.embedded.PrefixInstaller.Listener() {
                @Override public void onProgress(String stage, int percent) {
                    runOnUiThread(() -> {
                        if (!dialog.isShowing()) return;
                        status.setText(stage);
                        if (percent > 0) {
                            progress.setIndeterminate(false);
                            progress.setProgress(Math.min(100, percent));
                        }
                    });
                }
                @Override public void onComplete() {
                    runOnUiThread(() -> {
                        runtimeGateShowing = false;
                        ProjectManager.setCppEnabled(true);
                        statusText.setText("Linux runtime ready");
                        Toast.makeText(MainActivity.this,
                            "Linux runtime ready", Toast.LENGTH_LONG).show();
                        if (dialog.isShowing()) dialog.dismiss();
                        // Never drag someone who picked "Skip for now" into setup.
                        if (!runtimeGateSkipped) maybeShowSetupWizard();
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        if (!dialog.isShowing()) return;
                        progress.setVisibility(View.GONE);
                        status.setText("Download failed: " + message);
                        status.setTextColor(
                            ContextCompat.getColor(MainActivity.this, R.color.error));
                        downloadBtn.setVisibility(View.VISIBLE);
                        downloadBtn.setEnabled(true);
                        downloadBtn.setText("Retry");
                    });
                }
            });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The runtime may have been installed while we were away (wizard,
        // Settings) - re-evaluate the C++ gate every return. If it was
        // removed, the mandatory gate re-appears.
        ProjectManager.setCppEnabled(
            com.larv.ide.infrastructure.run.backend.embedded.EmbeddedRuntime.isEmbeddedReady(this));
        enforceRuntimeGate();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.hasExtra(com.larv.ide.ui.ProjectHomeActivity.EXTRA_PROJECT_PATH)) {
            String path = intent.getStringExtra(
                com.larv.ide.ui.ProjectHomeActivity.EXTRA_PROJECT_PATH);
            File dir = new File(path);
            if (dir.exists() && dir.isDirectory()) {
                projectManager.openProject(new Project(dir.getName(), dir.getAbsolutePath()));
            }
        }
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
        leftToolWindowContent.setClipToOutline(true);
        leftPanelWidthDp = prefs.getInt(PREF_LEFT_PANEL_WIDTH_DP, DEFAULT_LEFT_PANEL_WIDTH_DP);
        applyLeftPanelWidth(leftPanelWidthDp);

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
        projectSearchBar = findViewById(R.id.projectSearchBar);
        projectSearchInput = findViewById(R.id.projectSearchInput);
        fileTreeAdapter = new FileTreeAdapter(this, this);
        fileTreeRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        fileTreeRecyclerView.setAdapter(fileTreeAdapter);
        fileTreeDrag.setup();
        applyMenuBarGlass();

        bottomPanelAdapter = new BottomPanelAdapter(this);
        bottomViewPager.setAdapter(bottomPanelAdapter);
        bottomViewPager.setOffscreenPageLimit(4);

        new TabLayoutMediator(bottomTabLayout, bottomViewPager, (tab, position) -> {
            String title;
            switch (position) {
                case 1: title = "Problems"; break;
                case 2: title = "Preview"; break;
                case 3: title = "Terminal"; break;
                default: title = getString(R.string.run_code); break;
            }
            tab.setText(title);
        }).attach();

        noEditorPlaceholder.setVisibility(View.VISIBLE);
    }

    private void initServices() {
        projectManager = new ProjectManager(getApplicationContext(), taskScheduler.io());
        projectManager.setListener(this);

        embeddedBackend = new com.larv.ide.infrastructure.run.backend.embedded.EmbeddedLinuxBackend(
            getApplicationContext());
        runController = new RunController(this);
        runController.setEmbeddedBackend(embeddedBackend);
        ProjectManager.setCppEnabled(
            com.larv.ide.infrastructure.run.backend.embedded.EmbeddedRuntime.isEmbeddedReady(this));
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
        projectIndexer = new ProjectIndexer();
        cacheManager = com.larv.ide.infrastructure.performance.CacheManager.create(getApplicationContext());
        completionManager = new com.larv.ide.infrastructure.analysis.CompletionManager(
            projectIndexer, indexerExecutor);
        inspectionManager = new com.larv.ide.infrastructure.analysis.InspectionManager(
            indexerExecutor);
        lspManager = new com.larv.ide.infrastructure.analysis.LanguageServerManager(
            getApplicationContext(), embeddedBackend);
        lspManager.setDiagnosticsCallback((file, list) -> {
            lspDiagnostics.clear();
            lspDiagnostics.addAll(list);
            refreshProblems();
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupListeners() {
        btnNewFile.setOnClickListener(v -> {
            if (currentProject == null) {
                Toast.makeText(this, "Open a project first", Toast.LENGTH_SHORT).show();
            } else {
                projectActions.showNewFileDialog();
            }
        });
        btnNewFolder.setOnClickListener(v -> {
            if (currentProject == null) {
                Toast.makeText(this, "Open a project first", Toast.LENGTH_SHORT).show();
            } else {
                projectActions.showNewFolderDialog();
            }
        });
        btnRefreshProject.setOnClickListener(v -> projectManager.refreshFileTree());
        btnCollapseAll.setOnClickListener(v -> fileTreeAdapter.collapseAll());

        findViewById(R.id.btnProjectSearch).setOnClickListener(v -> {
            boolean show = projectSearchBar.getVisibility() != View.VISIBLE;
            projectSearchBar.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                projectSearchInput.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(projectSearchInput,
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            } else {
                projectSearchInput.setText("");
            }
        });
        findViewById(R.id.btnClearProjectSearch).setOnClickListener(v -> {
            projectSearchInput.setText("");
            projectSearchInput.requestFocus();
        });
        projectSearchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                fileTreeAdapter.setFilter(s.toString());
            }
        });
        findViewById(R.id.btnProjectSort).setOnClickListener(v -> {
            String mode = fileTreeAdapter.toggleSortMode();
            Toast.makeText(this, "Sort: " + mode, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnLocateFile).setOnClickListener(v -> highlightTreeFile(currentEditorFile));

        newTabButton.setOnClickListener(v -> {
            if (currentProject == null) {
                Toast.makeText(this, "Open a project first", Toast.LENGTH_SHORT).show();
            } else {
                projectActions.showNewFileDialog();
            }
        });
        btnSplitEditor.setOnClickListener(v -> toggleMaximizeEditor());
        btnRun.setOnClickListener(v -> compileAndRun());
        btnCloseProjectWindow.setOnClickListener(v -> closeLeftWindow());
        btnCloseBottomWindow.setOnClickListener(v -> closeBottomWindow());

        findViewById(R.id.btnWelcomeNewProject).setOnClickListener(v -> projectActions.startProjectCreator());
        findViewById(R.id.btnWelcomeOpenProject).setOnClickListener(v -> projectActions.showOpenProjectDialog());

        menuFile.setOnClickListener(v -> menuController.showMenuBarPopup(v, R.menu.menu_file, menuController::onMenuBarItemSelected, null));
        menuEdit.setOnClickListener(v -> menuController.showMenuBarPopup(v, R.menu.menu_edit, menuController::onMenuBarItemSelected, null));
        menuSearch.setOnClickListener(v -> menuController.showMenuBarPopup(v, R.menu.menu_search, menuController::onMenuBarItemSelected, null));
        menuView.setOnClickListener(v -> menuController.showMenuBarPopup(v, R.menu.menu_view, menuController::onMenuBarItemSelected,
            menuController::syncViewMenuState));
        menuBuild.setOnClickListener(v -> menuController.showMenuBarPopup(v, R.menu.menu_build, menuController::onMenuBarItemSelected, null));
        menuSettings.setOnClickListener(v -> menuController.showMenuBarPopup(v, R.menu.menu_settings, menuController::onMenuBarItemSelected, null));

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
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    leftResizeStartX = event.getRawX();
                    leftResizeStartWidth = leftToolWindowContent.getWidth();
                    leftResizeDownTime = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int movedDp = pxToDp(event.getRawX() - leftResizeStartX);
                    applyLeftPanelWidth(pxToDp(leftResizeStartWidth) + movedDp);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    // A quick tap without movement resets to the default width.
                    if (System.currentTimeMillis() - leftResizeDownTime < 300
                        && Math.abs(event.getRawX() - leftResizeStartX) < dp(8)) {
                        leftPanelWidthDp = DEFAULT_LEFT_PANEL_WIDTH_DP;
                        applyLeftPanelWidth(leftPanelWidthDp);
                    } else {
                        leftPanelWidthDp = pxToDp(leftToolWindowContent.getWidth());
                    }
                    prefs.edit().putInt(PREF_LEFT_PANEL_WIDTH_DP, leftPanelWidthDp).apply();
                    return true;
                default:
                    return false;
            }
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

    /**
     * Switch projects without losing work: flush dirty buffers to disk and
     * persist the OLD project's session BEFORE opening the new one.
     */
    void switchToProject(@NonNull Project project) {
        if (currentProject != null && !currentProject.getPath().equals(project.getPath())) {
            saveAllModifiedFilesSync();
            sessionManager.saveProjectNow(currentProject);
        }
        projectManager.openProject(project);
    }

    @Override
    public void onProjectOpened(@NonNull Project project) {
        currentProject = project;
        selectedDirectory = project.getPath();
        syncLanguageServers();
        if (prefs != null) {
            prefs.edit().putString(PREF_LAST_PROJECT, project.getPath()).apply();
        }
        closeAllOpenTabs();
        final boolean hadSession = sessionManager.hasSession(project);
        runOnUiThread(() -> {
            bottomToolWindow.setVisibility(editorMaximized ? View.GONE : View.VISIBLE);
            bottomWindowVisible = true;
            updateWindowTitle();
            showWelcomeStatus(true);
            showWelcome(false);
        });
        sessionManager.restore(project);
        // Open the creator-chosen entry first, else Main.java for fresh
        // projects - the user never lands on an empty editor.
        final String pendingEntry = projectActions.pendingEntryName;
        projectActions.pendingEntryName = null;
        if (pendingEntry != null && !pendingEntry.isEmpty()) {
            File entry = new File(project.getRootDir(), pendingEntry);
            if (entry.exists()) {
                runOnUiThread(() -> openFileInEditor(entry));
            }
        } else if (!hadSession) {
            File main = new File(project.getRootDir(), "Main.java");
            if (main.exists()) {
                runOnUiThread(() -> openFileInEditor(main));
            }
        }
        indexerExecutor.execute(() -> {
            ProjectRecognizer.Detection detection = ProjectRecognizer.detect(project.getRootDir(), "");
            if (!detection.languages.isEmpty()) {
                String joined = String.join(", ", detection.languages);
                runOnUiThread(() -> statusText.setText("Project: " + joined));
            }
            // Warm the symbol index in the background so the first completion
            // request in a freshly opened project does not scan every file.
            projectIndexer.indexProject(project.getRootDir());
            if (com.larv.ide.BuildConfig.DEBUG) {
                Log.d("LarvPerf", projectIndexer.performanceSnapshot());
            }
        });
    }

    @Override
    public void onProjectClosed() {
        if (currentProject != null) {
            saveAllModifiedFilesSync();
            sessionManager.saveProjectNow(currentProject);
        }
        currentProject = null;
        if (lspManager != null) lspManager.stopAll();
        lspDiagnostics.clear();
        sessionManager.reset();
        if (projectIndexer != null) projectIndexer.cancelIndexing();
        openFiles.clear();
        openFilesByPath.clear();
        runController.cancelPendingChecks();
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

    /** Debounced from onContentChange; no inspection work between keystrokes. */
    void scheduleInspection(String file, String content, boolean immediate) {
        if (inspectionManager == null || file == null || file.isEmpty()) return;
        com.larv.ide.infrastructure.analysis.InspectionConfig config =
            com.larv.ide.infrastructure.analysis.InspectionConfig.from(prefs);
        if (!config.live) {
            inspectionDiagnostics.clear();
            refreshProblems();
            return;
        }
        com.larv.ide.infrastructure.analysis.InspectionManager.Callback callback = (resultFile, list) -> {
            // Drop results for a file that is no longer visible.
            if (!resultFile.equals(currentEditorFile)) return;
            inspectionDiagnostics.clear();
            inspectionDiagnostics.addAll(list);
            refreshProblems();
        };
        if (immediate) {
            inspectionManager.requestNow(file, content, config, callback);
        } else {
            inspectionManager.request(file, content, config, callback);
        }
    }

    /** Start/stop optional language servers and open the active file. */
    void syncLanguageServers() {
        if (lspManager == null) return;
        if (currentProject == null) {
            lspManager.stopAll();
            return;
        }
        lspManager.configure(prefs, currentProject.getRootDir().getAbsolutePath());
        lspManager.sync();
        String lang = lspLangFor(currentEditorFile);
        if (lang == null || !com.larv.ide.infrastructure.analysis.LanguageServerManager
            .isEnabled(prefs, lang)) return;
        OpenFile open = findOpenFile(currentEditorFile);
        if (open != null) lspManager.open(lang, currentEditorFile, open.getContent());
    }

    private static String lspLangFor(String file) {
        if (file == null) return null;
        String f = file.toLowerCase(java.util.Locale.ROOT);
        if (f.endsWith(".java")) return "java";
        if (f.endsWith(".py")) return "python";
        if (f.endsWith(".c") || f.endsWith(".cc") || f.endsWith(".cpp")
            || f.endsWith(".cxx") || f.endsWith(".h") || f.endsWith(".hpp")) return "cpp";
        if (f.endsWith(".js") || f.endsWith(".jsx") || f.endsWith(".ts")
            || f.endsWith(".tsx")) return "typescript";
        return null;
    }

    /** Merge compiler errors, inspections and LSP diagnostics into one list. */
    private void refreshProblems() {
        List<com.larv.ide.infrastructure.model.Diagnostic> merged =
            new ArrayList<>(compilerDiagnostics);
        List<com.larv.ide.infrastructure.model.Diagnostic> allExtra =
            new ArrayList<>(inspectionDiagnostics);
        allExtra.addAll(lspDiagnostics);
        for (com.larv.ide.infrastructure.model.Diagnostic d : allExtra) {
            boolean duplicate = false;
            for (com.larv.ide.infrastructure.model.Diagnostic existing : merged) {
                if (existing.getLine() == d.getLine()
                    && existing.getMessage().equals(d.getMessage())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) merged.add(d);
        }
        final List<com.larv.ide.infrastructure.model.Diagnostic> result = merged;
        runOnUiThread(() -> {
            if (editorFragment != null) editorFragment.showDiagnostics(result);
            if (bottomPanelAdapter != null) {
                bottomPanelAdapter.getErrorsFragment().setErrors(result);
            }
        });
    }

    void setCompilerDiagnostics(List<com.larv.ide.infrastructure.model.Diagnostic> list) {
        compilerDiagnostics.clear();
        compilerDiagnostics.addAll(list);
        refreshProblems();
    }

    void clearProblems() {
        compilerDiagnostics.clear();
        inspectionDiagnostics.clear();
        lspDiagnostics.clear();
        if (inspectionManager != null) inspectionManager.cancelPending();
        if (editorFragment != null) editorFragment.clearDiagnostics();
        if (bottomPanelAdapter != null) {
            bottomPanelAdapter.getErrorsFragment().setErrors(new ArrayList<>());
        }
    }

    @Override
    public void onFileClick(@NonNull FileNode node) {
        if (node.getType() == FileNode.Type.DIRECTORY) {
            selectedDirectory = node.getPath();
            fileTreeAdapter.toggleExpansion(node);
        } else if (node.getType() == FileNode.Type.FILE) {
            openFileInEditor(new File(node.getPath()));
        }
    }

    @Override
    public void onFileMoreClick(FileNode node, View anchor) {
        projectActions.showFileContextMenu(node, anchor);
    }

    public void onContentChange(String file, String content) {
        OpenFile openFile = findOpenFile(file);
        if (openFile != null) {
            boolean changed = !openFile.getContent().equals(content);
            boolean newlyModified = changed && !openFile.isModified();
            openFile.setContent(content);
            if (changed) {
                openFile.setModified(true);
                String fileName = fileNameOf(file);
                runOnUiThread(() -> {
                    if (newlyModified) {
                        // The tab already reads "* name" after the first edit.
                        updateTabModified(file, true);
                        statusText.setText("Updated: " + fileName);
                    }
                    scheduleRunActionState(file, content);
                    if (prefs.getBoolean("autosaveEnabled", true)) {
                        autosaveHandler.removeCallbacks(autosaveRunnable);
                        autosaveHandler.postDelayed(autosaveRunnable, 1000);
                    }
                    scheduleInspection(file, content, false);
                    String lspLang = lspLangFor(file);
                    if (lspLang != null && lspManager != null) {
                        lspManager.change(lspLang, file, content);
                    }
                });
            }
        }
        sessionManager.scheduleSave();
    }

    private static String fileNameOf(String path) {
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return cut >= 0 ? path.substring(cut + 1) : path;
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
        completionManager.request(file, line, column, prefix, memberOf, content,
            callback::onCompletions);
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

    /**
     * Glassy menu bar: a translucent accent gradient with rounded bottom
     * corners. Rebuilt from the current accent so it follows Settings instantly
     * after the activity is recreated. No blur effect on the view itself — that
     * would blur the menu labels; the translucency + gradient is the cheap,
     * low-end-safe way to get the same look.
     */
    private void applyMenuBarGlass() {
        View bar = findViewById(R.id.menuBar);
        if (bar == null) return;
        int accent = themeColorOrFallback(
            com.google.android.material.R.attr.colorPrimary, R.color.ide_accent);
        int r = Color.red(accent);
        int g = Color.green(accent);
        int b = Color.blue(accent);
        int radius = dp(14);
        GradientDrawable glass = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{
                Color.argb(70, r, g, b),
                Color.argb(150, 22, 23, 26),
                Color.argb(45, r, g, b)
            });
        glass.setCornerRadii(new float[]{
            0, 0, 0, 0,
            radius, radius, radius, radius
        });
        glass.setStroke(1, Color.argb(70, r, g, b));
        bar.setBackground(glass);
    }

    private int themeColor(int attr) {
        if (attr == android.R.attr.colorPrimary
            && "custom".equals(prefs.getString("appAccent", "blue"))) {
            int c = prefs.getInt("customAccentColor", 0);
            if (c != 0) return c;
        }
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

    /** Push saved prefs (font size, theme, etc.) to the live editor. Idempotent. */
    void applyPrefsToEditor() {
        if (editorFragment != null) {
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
    }

    @Override
    public void onEditorReady() {
        if (editorFragment != null) {
            if (sessionManager.hasPendingCursorPositions()) {
                editorFragment.setCursorPositions(sessionManager.consumePendingCursorPositions());
            }
            applyPrefsToEditor();
        }
        if (!currentEditorFile.isEmpty()) {
            OpenFile openFile = findOpenFile(currentEditorFile);
            if (openFile != null && editorFragment != null) {
                editorFragment.setContent(currentEditorFile, openFile.getContent());
            }
        }
    }

    void openFileInEditor(@NonNull File file) {
        editorTabs.openFileInEditor(file);
    }

    private void ensureEditorFragment() {
        editorTabs.ensureEditorFragment();
    }

    private void switchToEditorTab(int index) {
        editorTabs.switchToTab(index);
    }

    private void closeEditorTab(int index) {
        editorTabs.closeTab(index);
    }

    private void updateTabModified(String filePath, boolean modified) {
        editorTabs.updateTabModified(filePath, modified);
    }

    private int indexOfOpenFile(String filePath) {
        return editorTabs.indexOfOpenFile(filePath);
    }

    @Nullable
    OpenFile findOpenFile(String filePath) {
        return editorTabs.findOpenFile(filePath);
    }

    private void updateRunActionState(String filePath, String content) {
        runController.updateRunActionState(filePath, content);
    }

    private void scheduleRunActionState(String filePath, String content) {
        runController.scheduleRunActionState(filePath, content);
    }

    private void setRunEnabled(boolean enabled) {
        runController.setRunEnabled(enabled);
    }

    private void compileAndRun() {
        runController.compileAndRun();
    }

    private RunDispatcher.RunStreams openRunTerminal(String statusLine) {
        return runController.openRunTerminal(statusLine);
    }

    private void writeTerm(OutputStream out, String text) {
        runController.writeTerm(out, text);
    }

    private void closeTermStreams(OutputStream programOutputOut, OutputStream programInputOut) {
        runController.closeTermStreams(programOutputOut, programInputOut);
    }

    private void showTabCloseMenu(int index, View anchor) {
        editorTabs.showTabCloseMenu(index, anchor);
    }

    private void closeOtherTabs(int keepIndex) {
        editorTabs.closeOtherTabs(keepIndex);
    }

    private void closeAllTabs() {
        editorTabs.closeAllTabs();
    }

    void showSettingsDialog() {
        MainDialogs.showSettings(this, prefs,
            themeColorOrFallback(
                com.google.android.material.R.attr.colorPrimary, R.color.ide_accent),
            this::applyPrefsToEditor,
            this::recreate,
            () -> {
                OpenFile open = findOpenFile(currentEditorFile);
                if (open != null) scheduleInspection(currentEditorFile, open.getContent(), true);
            },
            this::syncLanguageServers);
    }

    private void showWelcomeStatus(boolean projectOpen) {
        statusText.setText(projectOpen ? "Ready" : "Ready");
    }

    private void saveCurrentFile() {
        editorTabs.saveCurrentFile();
    }

    private void saveAllFiles() {
        editorTabs.saveAllFiles();
    }

    private void autoSaveModifiedFiles() {
        editorTabs.autoSaveModifiedFiles();
    }

    void saveAllModifiedFilesSync() {
        editorTabs.saveAllModifiedFilesSync();
    }

    void saveAllModifiedFilesSyncQuiet() {
        editorTabs.saveAllModifiedFilesSyncQuiet();
    }

    protected void onStop() {
        super.onStop();
        autosaveHandler.removeCallbacks(autosaveRunnable);
        // Flush synchronously, but off the main thread so StrictMode (debug builds)
        // does not flag disk I/O and the UI thread is not the one touching disk.
        Thread flushThread = new Thread(() -> {
            editorTabs.saveAllModifiedFilesSyncQuiet();
            sessionManager.saveNowSync();
        }, "onstop-flush");
        flushThread.start();
        try {
            flushThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (com.larv.ide.BuildConfig.DEBUG && projectManager != null) {
            Log.d("LarvPerf", projectManager.performanceSnapshot());
        }
    }

    /** In-app Linux runtime setup: download Termux bootstrap (arm64, WiFi) - no external app. */
    void showEmbeddedSetupDialog() {
        MainDialogs.showEmbeddedSetup(this, embeddedBackend, prefs, statusText);
    }

    private java.util.List<com.larv.ide.infrastructure.model.Diagnostic> parseCompilerDiagnostics(String output) {
        return runController.parseCompilerDiagnostics(output);
    }

    private void stopRunningProcess() {
        runController.stopRunningProcess();
    }

    private void openFullscreenTerminal() {
        runController.openFullscreenTerminal();
    }

    private void openEmbeddedTerminal() {
        runController.openEmbeddedTerminal();
    }

    private void closeAllOpenTabs() {
        editorTabs.closeAllOpenTabs();
    }

    private void updateWindowTitle() {
        editorTabs.updateWindowTitle();
        TextView projectTitle = findViewById(R.id.projectTitle);
        if (projectTitle != null) {
            projectTitle.setText(currentProject != null
                ? currentProject.getName() : getString(R.string.project));
        }
    }

    /** Select and scroll the project tree to the file open in the editor. */
    void highlightTreeFile(String path) {
        if (path == null || path.isEmpty() || fileTreeAdapter == null) return;
        int position = fileTreeAdapter.revealPath(path);
        if (position >= 0) {
            fileTreeRecyclerView.scrollToPosition(position);
            fileTreeAdapter.setSelectedPath(path);
        }
    }

    private static void deleteRecursive(File f) {
        MainDialogs.deleteRecursive(f);
    }

    @NonNull
    private EditText createIdeInput(String hint) {
        return MainDialogs.createIdeInput(this, hint);
    }

    @NonNull
    private View wrapDialogView(View view) {
        return MainDialogs.wrapDialogView(this, view);
    }

    private int pxToDp(float px) {
        return Math.round(px / getResources().getDisplayMetrics().density);
    }

    /** Clamp and apply the project panel width (IntelliJ-style resizing). */
    private void applyLeftPanelWidth(int widthDp) {
        int screenWidthDp = Math.round(getResources().getDisplayMetrics().widthPixels
            / Math.max(1f, getResources().getDisplayMetrics().density));
        int maxDp = Math.max(180, Math.min(480, screenWidthDp - 220));
        int clamped = Math.max(140, Math.min(maxDp, widthDp));
        ViewGroup.LayoutParams params = leftToolWindowContent.getLayoutParams();
        if (params == null) return;
        params.width = dp(clamped);
        leftToolWindowContent.setLayoutParams(params);
    }

    private int dp(int v) {
        return MainDialogs.dp(this, v);
    }

    void showHelpDialog() {
        MainDialogs.showHelp(this);
    }

    void showAboutDialog() {
        MainDialogs.showAbout(this, themeColor(android.R.attr.colorPrimary));
    }

    void toggleLeftWindow() {
        leftWindowVisible = !leftWindowVisible;
        leftToolWindowContent.setVisibility(leftWindowVisible ? View.VISIBLE : View.GONE);
        leftResizer.setVisibility(leftWindowVisible ? View.VISIBLE : View.GONE);
    }

    private void closeLeftWindow() {
        leftWindowVisible = false;
        leftToolWindowContent.setVisibility(View.GONE);
        leftResizer.setVisibility(View.GONE);
    }

    void toggleBottomWindow() {
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

    void toggleMaximizeEditor() {
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
        runController.cancelPendingChecks();
        taskScheduler.shutdown();
        if (completionManager != null) completionManager.cancelPending();
        if (inspectionManager != null) inspectionManager.cancelPending();
        if (lspManager != null) lspManager.stopAll();
        if (projectIndexer != null) {
            projectIndexer.clear();
        }
        if (cacheManager != null) cacheManager.clearAll();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (cacheManager == null) return;
        cacheManager.onTrimMemory(level);
        // Parse caches are the biggest disposable allocation on this screen;
        // symbol tables stay so completion never needs a cold scan.
        if (projectIndexer != null) projectIndexer.trimCaches();
    }
}
