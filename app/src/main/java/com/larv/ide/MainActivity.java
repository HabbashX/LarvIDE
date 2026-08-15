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
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
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
    private static final int REQUEST_PICK_FOLDER = 1002;

    private MaterialToolbar toolbar;
    private MaterialCardView fileTreeCard;
    private MaterialCardView editorCard;
    private MaterialCardView bottomPanel;
    private FloatingActionButton fabRun;
    private View resizeHandle;
    
    private androidx.recyclerview.widget.RecyclerView fileTreeRecyclerView;
    private FileTreeAdapter fileTreeAdapter;
    private TabLayout tabLayout;
    private ImageButton newTabButton;
    private TabLayout bottomTabLayout;
    private ViewPager2 bottomViewPager;
    private BottomPanelAdapter bottomPanelAdapter;
    
    private Map<String, EditorFragment> editorFragments = new HashMap<>();
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
    private String pendingMainClass = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            initServices();
        }
        setupListeners();
        checkPermissions();
        
        // Handle intent for opening files
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
                // Open the file in editor
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
        
        fileTreeCard = findViewById(R.id.fileTreeCard);
        editorCard = findViewById(R.id.editorCard);
        bottomPanel = findViewById(R.id.bottomPanel);
        fabRun = findViewById(R.id.fabRun);
        resizeHandle = findViewById(R.id.resizeHandle);
        
        fileTreeRecyclerView = findViewById(R.id.fileTreeRecyclerView);
        tabLayout = findViewById(R.id.tabLayout);
        newTabButton = findViewById(R.id.newTabButton);
        bottomTabLayout = findViewById(R.id.bottomTabLayout);
        bottomViewPager = findViewById(R.id.bottomViewPager);
        
        // File tree adapter
        fileTreeAdapter = new FileTreeAdapter(this);
        fileTreeRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        fileTreeRecyclerView.setAdapter(fileTreeAdapter);
        
        // Bottom panel adapter
        bottomPanelAdapter = new BottomPanelAdapter(this);
        bottomViewPager.setAdapter(bottomPanelAdapter);
        bottomViewPager.setOffscreenPageLimit(2);
        
        new TabLayoutMediator(bottomTabLayout, bottomViewPager, (tab, position) -> {
            tab.setText(position == 0 ? getString(R.string.output_title) : getString(R.string.errors_title));
        }).attach();
        
        // New tab button
        newTabButton.setOnClickListener(v -> showNewFileDialog());
        
        // FAB Run
        fabRun.setOnClickListener(v -> compileAndRun());
        
        // Resize handle for sidebar
        setupResizeHandle();
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void initServices() {
        projectManager = new ProjectManager(this);
        projectManager.setListener(this);
        
        javaCompiler = new JavaCompiler(this);
        dexer = new Dexer(this);
        javaRunner = new JavaRunner(this);
        projectIndexer = new ProjectIndexer();
    }

    private void setupListeners() {
        // Tab layout listener
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
        
        // Tab long press for close menu
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                tab.view.setOnLongClickListener(v -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        showTabCloseMenu(tab.getPosition(), v);
                    }
                    return true;
                });
            }
            
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupResizeHandle() {
        resizeHandle.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float x = event.getRawX();
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int minSidebar = 200;
                int maxSidebar = screenWidth - 400;
                int newWidth = Math.max(minSidebar, Math.min(maxSidebar, (int) x));

                ViewGroup.LayoutParams params = fileTreeCard.getLayoutParams();
                params.width = newWidth;
                fileTreeCard.setLayoutParams(params);
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
                startActivityForResult(intent, REQUEST_STORAGE_PERMISSION);
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
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onProjectOpened(Project project) {
        currentProject = project;
        runOnUiThread(() -> {
            getSupportActionBar().setSubtitle(project.getName());
            fabRun.setVisibility(View.VISIBLE);
            bottomPanel.setVisibility(View.VISIBLE);
            updateWindowTitle();
        });
    }

    @Override
    public void onProjectClosed() {
        currentProject = null;
        openFiles.clear();
        editorFragments.clear();
        currentEditorFile = "";
        runOnUiThread(() -> {
            getSupportActionBar().setSubtitle(null);
            fabRun.setVisibility(View.GONE);
            bottomPanel.setVisibility(View.GONE);
            tabLayout.removeAllTabs();
            updateWindowTitle();
        });
    }

    @Override
    public void onFileTreeUpdated(List<FileNode> rootNodes) {
        runOnUiThread(() -> fileTreeAdapter.setRootNodes(rootNodes));
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onFileClick(@NonNull FileNode node) {
        if (node.getType() == FileNode.Type.DIRECTORY) {
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
            openFile.setContent(content);
            openFile.setModified(true);
            updateTabModified(file, true);
            
            // Trigger type checking
            scheduleTypeCheck();
        }
    }

    @Override
    public void onCursorChange(int line, int column) {
        // Update cursor position in OpenFile
        OpenFile openFile = findOpenFile(currentEditorFile);
        if (openFile != null) {
            openFile.setCursorLine(line);
            openFile.setCursorColumn(column);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onCompletionsRequested(String file, int line, int column, EditorFragment.CompletionCallback callback) {
        List<CompletionItem> completions = projectIndexer.getCompletions("", file, line, column);
        callback.onCompletions(completions);
    }

    @Override
    public void onEditorReady() {
        // Editor is ready, load content if needed
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
        
        // Check if already open
        OpenFile existing = findOpenFile(filePath);
        if (existing != null) {
            switchToEditorTab(openFiles.indexOf(existing));
            return;
        }
        
        projectManager.readFile(file, new ProjectManager.OnFileReadCallback() {
            @RequiresApi(api = Build.VERSION_CODES.N)
            @Override
            public void onContent(String content) {
                runOnUiThread(() -> {
                    OpenFile openFile = new OpenFile(filePath, content);
                    openFiles.add(openFile);
                    
                    int index = openFiles.size() - 1;
                    TabLayout.Tab tab = tabLayout.newTab().setText(file.getName());
                    tabLayout.addTab(tab, index, true);
                    
                    // Create editor fragment
                    EditorFragment fragment = new EditorFragment();
                    fragment.setListener(MainActivity.this);
                    editorFragments.put(filePath, fragment);
                    
                    // Add to fragment manager
                    FragmentManager fm = getSupportFragmentManager();
                    fm.beginTransaction()
                        .add(R.id.editorCard, fragment, "editor_" + index)
                        .commit();
                    
                    // Switch to new tab
                    tabLayout.selectTab(tab);
                    
                    // Index for completions
                    projectIndexer.indexFile(openFile);
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
        
        // Hide all editor fragments
        FragmentManager fm = getSupportFragmentManager();
        for (EditorFragment fragment : editorFragments.values()) {
            fm.beginTransaction().hide(fragment).commit();
        }
        
        // Show selected fragment
        EditorFragment fragment = editorFragments.get(filePath);
        if (fragment != null) {
            fm.beginTransaction().show(fragment).commit();
            fragment.setContent(filePath, findOpenFile(filePath).getContent());
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void closeEditorTab(int index) {
        if (index < 0 || index >= openFiles.size()) return;
        
        OpenFile openFile = openFiles.get(index);
        String filePath = openFile.getFilePath();
        
        // Remove from project indexer
        projectIndexer.removeFile(openFile.getFileName());
        
        // Remove fragment
        EditorFragment fragment = editorFragments.remove(filePath);
        if (fragment != null) {
            getSupportFragmentManager().beginTransaction().remove(fragment).commit();
        }
        
        // Remove from open files
        openFiles.remove(index);
        
        // Remove tab
        tabLayout.removeTabAt(index);
        
        // Update indices for remaining tabs
        for (int i = index; i < openFiles.size(); i++) {
            TabLayout.Tab tab = tabLayout.getTabAt(i);
            if (tab != null) {
                // Tab text updates automatically
            }
        }
        
        // Select adjacent tab
        if (!openFiles.isEmpty()) {
            int newIndex = Math.min(index, openFiles.size() - 1);
            tabLayout.selectTab(tabLayout.getTabAt(newIndex));
        } else {
            currentEditorFile = "";
        }
    }

    private void updateTabModified(String filePath, boolean modified) {
        int index = openFiles.indexOf(findOpenFile(filePath));
        if (index >= 0) {
            TabLayout.Tab tab = tabLayout.getTabAt(index);
            if (tab != null) {
                String name = openFiles.get(index).getFileName();
                tab.setText(modified ? "● " + name : name);
            }
        }
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


    private void compileAndRun() {
        if (isCompiling || openFiles.isEmpty()) return;
        
        isCompiling = true;
        runOnUiThread(() -> {
            fabRun.setEnabled(false);
            fabRun.setImageResource(android.R.drawable.ic_media_pause);
            bottomPanelAdapter.getOutputFragment().clear();
            bottomPanelAdapter.getErrorsFragment().setErrors(new ArrayList<>());
            bottomViewPager.setCurrentItem(0);
        });
        
        compilerExecutor.execute(() -> {
            // Compile
            JavaCompiler.CompilationResult compileResult = javaCompiler.compile(openFiles);
            
            runOnUiThread(() -> {
                bottomPanelAdapter.getErrorsFragment().setErrors(compileResult.getDiagnostics());
                
                if (!compileResult.isSuccess()) {
                    isCompiling = false;
                    fabRun.setEnabled(true);
                    fabRun.setImageResource(android.R.drawable.ic_media_play);
                    bottomViewPager.setCurrentItem(1);
                    return;
                }
                
                bottomPanelAdapter.getOutputFragment().addLine("Compilation successful");
                
                // Dex
                Dexer.DexResult dexResult = null;
                dexResult = dexer.dex(compileResult.getClassFiles(), null);

                if (!dexResult.isSuccess()) {
                    bottomPanelAdapter.getOutputFragment().addLine("Dex error: " + dexResult.getError());
                    isCompiling = false;
                    fabRun.setEnabled(true);
                    fabRun.setImageResource(android.R.drawable.ic_media_play);
                    return;
                }
                
                bottomPanelAdapter.getOutputFragment().addLine("Dex successful, running...");
                
                // Find main class
                String mainClass = findMainClass(openFiles);
                if (mainClass == null) {
                    bottomPanelAdapter.getOutputFragment().addLine("Error: No main class found");
                    isCompiling = false;
                    fabRun.setEnabled(true);
                    fabRun.setImageResource(android.R.drawable.ic_media_play);
                    return;
                }
                
                // Run
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
                    fabRun.setEnabled(true);
                    fabRun.setImageResource(android.R.drawable.ic_media_play);
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
        // Debounced type check
        compilerExecutor.execute(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            
            runOnUiThread(() -> {
                JavaCompiler.CompilationResult result = javaCompiler.typeCheck(openFiles);
                bottomPanelAdapter.getErrorsFragment().setErrors(result.getDiagnostics());
                
                // Also show diagnostics in editor
                if (!openFiles.isEmpty() && !currentEditorFile.isEmpty()) {
                    EditorFragment fragment = editorFragments.get(currentEditorFile);
                    if (fragment != null) {
                        fragment.showDiagnostics(result.getDiagnostics());
                    }
                }
            });
        });
    }

    // ============ UI Dialogs ============
    
    private void showNewFileDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("New Java File");
        
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("File name (e.g., MyClass.java)");
        builder.setView(input);
        
        builder.setPositiveButton("Create", (dialog, which) -> {
            String fileName = input.getText().toString().trim();
            if (!fileName.isEmpty()) {
                if (!fileName.endsWith(".java")) fileName += ".java";
                if (currentProject != null) {
                    projectManager.createFile(currentProject.getPath(), fileName, 
                        new ProjectManager.OnFileOperationCallback() {
                            @Override
                            public void onSuccess(File file) {
                                openFileInEditor(file);
                            }
                            @Override
                            public void onError(String error) {
                                Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show();
                            }
                        });
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showFileContextMenu(FileNode node) {
        PopupMenu popup = new PopupMenu(this, findViewById(android.R.id.content), Gravity.CENTER);
        popup.getMenu().add("Open").setOnMenuItemClickListener(item -> {
            openFileInEditor(new File(node.getPath()));
            return true;
        });
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

    @RequiresApi(api = Build.VERSION_CODES.N)
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    closeEditorTab(i);
                }
            }
        }
    }

    private void closeAllTabs() {
        for (int i = openFiles.size() - 1; i >= 0; i--) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                closeEditorTab(i);
            }
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
                            Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show();
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
                        }
                        @Override
                        public void onError(String error) {
                            Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show();
                        }
                    });
            })
            .setNegativeButton("Cancel", null)
            .show();
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
                        Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show();
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
            .setItems(names, (dialog, which) -> {
                projectManager.openProject(projects.get(which));
            })
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
                            Toast.makeText(MainActivity.this, "Saved", Toast.LENGTH_SHORT).show();
                        }
                        @Override
                        public void onError(String error) {
                            Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show();
                        }
                    });
            }
        }
    }

    private void updateWindowTitle() {
        String title = getString(R.string.app_name);
        if (currentProject != null) {
            title += " - " + currentProject.getName();
        }
        setTitle(title);
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
        } else if (id == R.id.menu_compile) {
            compileAndRun();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        compilerExecutor.shutdown();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            projectIndexer.clear();
        }
    }
}