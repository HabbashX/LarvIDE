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
import android.text.InputType;
import android.view.Gravity;
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
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.larv.ide.compiler.Dexer;
import com.larv.ide.compiler.JavaCompiler;
import com.larv.ide.compiler.JavaRunner;
import com.larv.ide.completion.CompletionItem;
import com.larv.ide.completion.ProjectIndexer;
import com.larv.ide.model.Diagnostic;
import com.larv.ide.model.FileNode;
import com.larv.ide.model.OpenFile;
import com.larv.ide.model.Project;
import com.larv.ide.project.ProjectManager;
import com.larv.ide.ui.adapter.BottomPanelAdapter;
import com.larv.ide.ui.adapter.FileTreeAdapter;
import com.larv.ide.ui.fragment.EditorFragment;


import java.io.File;
import java.io.IOException;
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

    private MaterialToolbar toolbar;

    // Left tool window
    private View leftToolWindowContent;
    private FrameLayout projectToolWindow;
    private ImageButton btnNewFile;
    private ImageButton btnNewFolder;
    private ImageButton btnRefreshProject;
    private ImageButton btnCollapseAll;
    private ImageButton btnProjectSettings;

    // Editor
    private FrameLayout editorContainer;
    private View noEditorPlaceholder;
    private TabLayout tabLayout;
    private ImageButton newTabButton;
    private ImageButton btnSplitEditor;

    // Bottom tool window
    private FrameLayout bottomToolWindow;
    private TabLayout bottomTabLayout;
    private ViewPager2 bottomViewPager;
    private BottomPanelAdapter bottomPanelAdapter;

    // Status bar
    private android.widget.TextView statusText;
    private android.widget.TextView statusPosition;

    // Resizers
    private View leftResizer;
    private View bottomResizer;

    private androidx.recyclerview.widget.RecyclerView fileTreeRecyclerView;
    private FileTreeAdapter fileTreeAdapter;

    private final Map<String, EditorFragment> editorFragments = new HashMap<>();
    private String currentEditorFile = "";
    private ProjectManager projectManager;
    private JavaCompiler javaCompiler;
    private Dexer dexer;
    private JavaRunner javaRunner;
    private ProjectIndexer projectIndexer;
    private final ExecutorService compilerExecutor = Executors.newSingleThreadExecutor();

    // State
    private Project currentProject;
    private final List<OpenFile> openFiles = new ArrayList<>();
    private boolean isCompiling = false;
    private String selectedDirectory = "";
    private boolean leftWindowVisible = true;
    private boolean bottomWindowVisible = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initServices();
        setupListeners();
        checkPermissions();

        handleIntent(getIntent());
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

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Left tool window
        leftToolWindowContent = findViewById(R.id.leftToolWindowContent);
        projectToolWindow = findViewById(R.id.projectToolWindow);
        btnNewFile = findViewById(R.id.btnNewFile);
        btnNewFolder = findViewById(R.id.btnNewFolder);
        btnRefreshProject = findViewById(R.id.btnRefreshProject);
        btnCollapseAll = findViewById(R.id.btnCollapseAll);
        btnProjectSettings = findViewById(R.id.btnProjectSettings);
        leftResizer = findViewById(R.id.leftResizer);

        // Editor
        editorContainer = findViewById(R.id.editorContainer);
        noEditorPlaceholder = findViewById(R.id.noEditorPlaceholder);
        tabLayout = findViewById(R.id.tabLayout);
        newTabButton = findViewById(R.id.newTabButton);
        btnSplitEditor = findViewById(R.id.btnSplitEditor);

        // Bottom tool window
        bottomToolWindow = findViewById(R.id.bottomToolWindow);
        bottomTabLayout = findViewById(R.id.bottomTabLayout);
        bottomViewPager = findViewById(R.id.bottomViewPager);
        bottomResizer = findViewById(R.id.bottomResizer);

        // Status bar
        statusText = findViewById(R.id.statusText);
        statusPosition = findViewById(R.id.statusPosition);

        // File tree
        fileTreeRecyclerView = findViewById(R.id.fileTreeRecyclerView);
        fileTreeAdapter = new FileTreeAdapter(this);
        fileTreeRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        fileTreeRecyclerView.setAdapter(fileTreeAdapter);

        // Bottom panel adapter
        bottomPanelAdapter = new BottomPanelAdapter(this);
        bottomViewPager.setAdapter(bottomPanelAdapter);
        bottomViewPager.setOffscreenPageLimit(2);

        new TabLayoutMediator(bottomTabLayout, bottomViewPager, (tab, position) -> {
            tab.setText(position == 0 ? getString(R.string.run_code) : getString(R.string.errors_title));
        }).attach();

        noEditorPlaceholder.setVisibility(View.VISIBLE);
    }

    private void initServices() {
        projectManager = new ProjectManager(this);
        projectManager.setListener(this);

        javaCompiler = new JavaCompiler(this);
        dexer = new Dexer(this);
        javaRunner = new JavaRunner(this);
        projectIndexer = new ProjectIndexer();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupListeners() {
        // Tool window buttons
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
        btnProjectSettings.setOnClickListener(v -> showSettingsDialog());

        newTabButton.setOnClickListener(v -> {
            if (currentProject == null) {
                Toast.makeText(this, "Open a project first", Toast.LENGTH_SHORT).show();
            } else {
                showNewFileDialog();
            }
        });
        btnSplitEditor.setOnClickListener(v ->
            Toast.makeText(this, "Split editor not yet available", Toast.LENGTH_SHORT).show());

        // Editor tabs
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

        // Left resizer
        leftResizer.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float x = event.getRawX();
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int newWidth = (int) x;
                ViewGroup.LayoutParams params = leftToolWindowContent.getLayoutParams();
                params.width = Math.max(180, Math.min(screenWidth - 400, newWidth));
                leftToolWindowContent.setLayoutParams(params);
                return true;
            }
            return false;
        });

        // Bottom resizer
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

    // ============ ProjectManager.OnProjectChangeListener ============

    @Override
    public void onProjectOpened(Project project) {
        currentProject = project;
        selectedDirectory = project.getPath();
        runOnUiThread(() -> {
            getSupportActionBar().setSubtitle(project.getName());
            bottomToolWindow.setVisibility(View.VISIBLE);
            bottomWindowVisible = true;
            updateWindowTitle();
            showWelcomeStatus(true);
        });
    }

    @Override
    public void onProjectClosed() {
        currentProject = null;
        openFiles.clear();
        editorFragments.clear();
        currentEditorFile = "";
        selectedDirectory = "";
        runOnUiThread(() -> {
            getSupportActionBar().setSubtitle(null);
            bottomToolWindow.setVisibility(View.GONE);
            bottomWindowVisible = false;
            tabLayout.removeAllTabs();
            editorContainer.removeAllViews();
            noEditorPlaceholder.setVisibility(View.VISIBLE);
            updateWindowTitle();
            statusText.setText("No project");
        });
    }

    @Override
    public void onFileTreeUpdated(List<FileNode> rootNodes) {
        runOnUiThread(() -> {
            fileTreeAdapter.setRootNodes(rootNodes);
            if (!selectedDirectory.isEmpty()) {
                fileTreeAdapter.expandPath(selectedDirectory);
            }
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    // ============ FileTreeAdapter.OnFileClickListener ============

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
    public void onFileLongClick(FileNode node) {
        showFileContextMenu(node);
    }

    // ============ EditorFragment.EditorListener ============

    @Override
    public void onContentChange(String file, String content) {
        OpenFile openFile = findOpenFile(file);
        if (openFile != null) {
            boolean changed = !openFile.getContent().equals(content);
            openFile.setContent(content);
            if (changed) {
                openFile.setModified(true);
                updateTabModified(file, true);
                statusText.setText("Updated: " + new File(file).getName());
                scheduleTypeCheck();
            }
        }
    }

    @Override
    public void onCursorChange(int line, int column) {
        OpenFile openFile = findOpenFile(currentEditorFile);
        if (openFile != null) {
            openFile.setCursorLine(line);
            openFile.setCursorColumn(column);
        }
        runOnUiThread(() -> statusPosition.setText("Ln " + line + ", Col " + column));
    }

    @Override
    public void onCompletionsRequested(String file, int line, int column, EditorFragment.CompletionCallback callback) {
        List<CompletionItem> completions = projectIndexer.getCompletions("", file, line, column);
        callback.onCompletions(completions);
    }

    @Override
    public void onEditorReady() {
        if (!currentEditorFile.isEmpty()) {
            OpenFile openFile = findOpenFile(currentEditorFile);
            if (openFile != null) {
                EditorFragment fragment = editorFragments.get(currentEditorFile);
                if (fragment != null) {
                    fragment.setContent(currentEditorFile, openFile.getContent());
                }
            }
        }
    }

    // ============ File Operations ============

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

                    int index = openFiles.size() - 1;
                    TabLayout.Tab tab = tabLayout.newTab().setText(file.getName());
                    tabLayout.addTab(tab, index, true);

                    EditorFragment fragment = new EditorFragment();
                    fragment.setListener(MainActivity.this);
                    editorFragments.put(filePath, fragment);

                    FragmentManager fm = getSupportFragmentManager();
                    fm.beginTransaction()
                        .add(R.id.editorContainer, fragment, "editor_" + index)
                        .commit();

                    tabLayout.selectTab(tab);
                    noEditorPlaceholder.setVisibility(View.GONE);
                    projectIndexer.indexFile(openFile);
                    statusText.setText(file.getName() + " - Loading");
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void switchToEditorTab(int index) {
        if (index < 0 || index >= openFiles.size()) return;

        String filePath = openFiles.get(index).getFilePath();
        currentEditorFile = filePath;

        FragmentManager fm = getSupportFragmentManager();
        for (EditorFragment fragment : editorFragments.values()) {
            fm.beginTransaction().hide(fragment).commit();
        }

        EditorFragment fragment = editorFragments.get(filePath);
        if (fragment != null) {
            fm.beginTransaction().show(fragment).commit();
            fragment.setContent(filePath, findOpenFile(filePath).getContent());
        }
        if (openFiles.isEmpty()) {
            noEditorPlaceholder.setVisibility(View.VISIBLE);
        }
    }

    private void closeEditorTab(int index) {
        if (index < 0 || index >= openFiles.size()) return;

        OpenFile openFile = openFiles.get(index);
        String filePath = openFile.getFilePath();

        projectIndexer.removeFile(openFile.getFileName());

        EditorFragment fragment = editorFragments.remove(filePath);
        if (fragment != null) {
            getSupportFragmentManager().beginTransaction().remove(fragment).commit();
        }

        openFiles.remove(index);
        tabLayout.removeTabAt(index);

        if (!openFiles.isEmpty()) {
            int newIndex = Math.min(index, openFiles.size() - 1);
            tabLayout.selectTab(tabLayout.getTabAt(newIndex));
        } else {
            currentEditorFile = "";
            noEditorPlaceholder.setVisibility(View.VISIBLE);
        }
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
        for (int i = 0; i < openFiles.size(); i++) {
            if (openFiles.get(i).getFilePath().equals(filePath)) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    private OpenFile findOpenFile(String filePath) {
        for (OpenFile f : openFiles) {
            if (f.getFilePath().equals(filePath)) {
                return f;
            }
        }
        return null;
    }

    // ============ Compilation & Execution ============

    private void compileAndRun() {
        if (isCompiling || openFiles.isEmpty()) return;

        isCompiling = true;
        runOnUiThread(() -> {
            statusText.setText("Compiling...");
            bottomPanelAdapter.getOutputFragment().clear();
            bottomPanelAdapter.getErrorsFragment().setErrors(new ArrayList<>());
            bottomViewPager.setCurrentItem(0);
            bottomToolWindow.setVisibility(View.VISIBLE);
        });

        compilerExecutor.execute(() -> {
            JavaCompiler.CompilationResult compileResult = javaCompiler.compile(openFiles);

            runOnUiThread(() -> {
                bottomPanelAdapter.getErrorsFragment().setErrors(compileResult.getDiagnostics());

                if (!compileResult.isSuccess()) {
                    isCompiling = false;
                    statusText.setText("Compilation failed");
                    bottomViewPager.setCurrentItem(1);
                    return;
                }

                bottomPanelAdapter.getOutputFragment().addLine("Compilation successful");

                Dexer.DexResult dexResult = dexer.dex(compileResult.getClassFiles(), null);

                if (!dexResult.isSuccess()) {
                    bottomPanelAdapter.getOutputFragment().addLine("Dex error: " + dexResult.getError());
                    isCompiling = false;
                    statusText.setText("Dex error");
                    return;
                }

                bottomPanelAdapter.getOutputFragment().addLine("Dex successful, running...");

                String mainClass = findMainClass(openFiles);
                if (mainClass == null) {
                    bottomPanelAdapter.getOutputFragment().addLine("Error: No main class found");
                    isCompiling = false;
                    statusText.setText("No main class");
                    return;
                }

                JavaRunner.RunResult runResult = javaRunner.run(dexResult.getDexFile(), mainClass, new String[]{});

                runOnUiThread(() -> {
                    if (runResult.isSuccess()) {
                        bottomPanelAdapter.getOutputFragment().addLine("Program finished (exit code 0)");
                        if (!runResult.getOutput().isEmpty()) {
                            for (String line : runResult.getOutput().split("\n")) {
                                bottomPanelAdapter.getOutputFragment().addLine(line);
                            }
                        }
                    } else {
                        bottomPanelAdapter.getOutputFragment().addLine("Runtime error: " + runResult.getError());
                        if (!runResult.getErrorOutput().isEmpty()) {
                            for (String line : runResult.getErrorOutput().split("\n")) {
                                bottomPanelAdapter.getOutputFragment().addLine(line);
                            }
                        }
                    }

                    isCompiling = false;
                    statusText.setText("Done");
                });
            });
        });
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
        compilerExecutor.execute(() -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                return;
            }

            runOnUiThread(() -> {
                if (openFiles.isEmpty()) return;
                JavaCompiler.CompilationResult result = javaCompiler.typeCheck(openFiles);
                bottomPanelAdapter.getErrorsFragment().setErrors(result.getDiagnostics());

                if (!currentEditorFile.isEmpty()) {
                    EditorFragment fragment = editorFragments.get(currentEditorFile);
                    if (fragment != null) {
                        fragment.showDiagnostics(result.getDiagnostics());
                    }
                }
            });
        });
    }

    // ============ UI Dialogs ============

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
        builder.setTitle("New Java File");
        builder.setMessage("In: " + new File(parentPath).getName());

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("File name (e.g., MyClass)");
        builder.setView(input);

        builder.setPositiveButton("Create", (dialog, which) -> {
            String fileName = input.getText().toString().trim();
            if (fileName.isEmpty()) {
                Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
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

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Folder name");
        builder.setView(input);

        builder.setPositiveButton("Create", (dialog, which) -> {
            String folderName = input.getText().toString().trim();
            if (folderName.isEmpty()) {
                Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            projectManager.createFolder(parentPath, folderName,
                new ProjectManager.OnFileOperationCallback() {
                    @Override
                    public void onSuccess(File file) {
                        selectedDirectory = file.getAbsolutePath();
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Folder created", Toast.LENGTH_SHORT).show();
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

    private void showFileContextMenu(FileNode node) {
        PopupMenu popup = new PopupMenu(this, findViewById(android.R.id.content), Gravity.CENTER);
        popup.getMenu().add(node.getName()).setEnabled(false);

        if (node.getType() == FileNode.Type.DIRECTORY) {
            popup.getMenu().add("New Java File").setOnMenuItemClickListener(item -> {
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

        EditText input = new EditText(this);
        input.setText(node.getName());
        builder.setView(input);

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
        new AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(new String[]{"Java 21 (recommended)", "Java 17", "Java 11"},
                (dialog, which) -> Toast.makeText(this, "Java level option coming soon", Toast.LENGTH_SHORT).show())
            .show();
    }

    private void showWelcomeStatus(boolean projectOpen) {
        statusText.setText(projectOpen ? "Ready" : "Ready");
    }

    private void showNewProjectDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("New Project");

        EditText input = new EditText(this);
        input.setHint("Project name");
        builder.setView(input);

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
                projectManager.writeFile(new File(openFile.getFilePath()), openFile.getContent(), null);
                openFile.setModified(false);
            }
        }
        statusText.setText("All files saved");
    }

    private void updateWindowTitle() {
        String title = getString(R.string.app_name);
        if (currentProject != null) {
            title += " - " + currentProject.getName();
        }
        setTitle(title);
    }

    private void toggleLeftWindow() {
        leftWindowVisible = !leftWindowVisible;
        leftToolWindowContent.setVisibility(leftWindowVisible ? View.VISIBLE : View.GONE);
        leftResizer.setVisibility(leftWindowVisible ? View.VISIBLE : View.GONE);
    }

    private void toggleBottomWindow() {
        bottomWindowVisible = !bottomWindowVisible;
        bottomToolWindow.setVisibility(bottomWindowVisible ? View.VISIBLE : View.GONE);
        bottomResizer.setVisibility(bottomWindowVisible ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_new_project) {
            showNewProjectDialog();
            return true;
        } else if (id == R.id.menu_open_project) {
            showOpenProjectDialog();
            return true;
        } else if (id == R.id.menu_save) {
            saveCurrentFile();
            return true;
        } else if (id == R.id.menu_save_all) {
            saveAllFiles();
            return true;
        } else if (id == R.id.menu_compile || id == R.id.menu_run) {
            compileAndRun();
            return true;
        } else if (id == R.id.menu_toggle_project) {
            item.setChecked(!item.isChecked());
            toggleLeftWindow();
            return true;
        } else if (id == R.id.menu_toggle_terminal) {
            item.setChecked(!item.isChecked());
            toggleBottomWindow();
            return true;
        } else if (id == R.id.menu_build || id == R.id.menu_rebuild) {
            compileAndRun();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        compilerExecutor.shutdown();
        if (projectIndexer != null) {
            projectIndexer.clear();
        }
    }
}