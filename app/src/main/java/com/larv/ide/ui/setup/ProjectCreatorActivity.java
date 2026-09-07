package com.larv.ide.ui.setup;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.larv.ide.R;
import com.larv.ide.project.ProjectManager;
import com.larv.ide.model.Project;
import com.larv.ide.run.backend.ExecRequest;
import com.larv.ide.run.backend.embedded.EmbeddedLinuxBackend;
import com.larv.ide.run.backend.embedded.EmbeddedRuntime;
import com.larv.ide.setup.SetupConfig;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-page project creator: name + language (scaffold) + toolchain install.
 * "Empty" builds a bare folder with no entry file and no toolchain.
 * Returns {@code project_path} + {@code entry_name} to the caller.
 */
public class ProjectCreatorActivity extends AppCompatActivity {

    public static final String EXTRA_PROJECT_PATH = "project_path";
    public static final String EXTRA_ENTRY_NAME = "entry_name";

    private static final String PREFS = "larv_ide";

    private EditText nameInput;
    private RadioGroup languageGroup;
    private TextView toolchainStatus;
    private Button toolchainAction;
    private Button createBtn;
    private TextView logView;

    private ProjectManager projectManager;
    private EmbeddedLinuxBackend backend;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    /** Parallel to the radio order: null = Empty, then SetupConfig order. */
    private SetupConfig.Language languageAt(int index) {
        if (index <= 0) return null;
        SetupConfig.Language[] values = SetupConfig.Language.values();
        int i = index - 1;
        return i < values.length ? values[i] : null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        projectManager = new ProjectManager(getApplicationContext());
        backend = new EmbeddedLinuxBackend(getApplicationContext());

        ScrollView scroller = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(16), pad, dp(16));
        scroller.addView(root);
        setContentView(scroller);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.background));

        TextView brand = new TextView(this);
        brand.setText("New Project");
        brand.setTextSize(22);
        brand.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(brand);

        root.addView(sectionLabel("Project name"));
        nameInput = new EditText(this);
        nameInput.setHint("MyApp");
        nameInput.setSingleLine(true);
        nameInput.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        nameInput.setHintTextColor(ContextCompat.getColor(this, R.color.text_disabled));
        nameInput.setBackground(ContextCompat.getDrawable(this, R.drawable.edittext_ide));
        root.addView(nameInput);

        root.addView(sectionLabel("Language & scaffold"));
        languageGroup = new RadioGroup(this);
        languageGroup.setOrientation(RadioGroup.VERTICAL);
        root.addView(languageGroup);

        addLanguageRow("Empty project", "Bare folder — no entry file, no toolchain needed.", true);
        for (SetupConfig.Language lang : SetupConfig.Language.values()) {
            addLanguageRow(lang.title + "  (" + SetupConfig.entryFileName(lang) + ")",
                lang.blurb + "  ·  toolchain: " + lang.pkg, false);
        }
        languageGroup.setOnCheckedChangeListener((g, id) -> refreshToolchainRow());

        root.addView(sectionLabel("Build tool"));
        toolchainStatus = new TextView(this);
        toolchainStatus.setTextSize(13);
        toolchainStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        root.addView(toolchainStatus);

        toolchainAction = new Button(this, null, 0);
        toolchainAction.setTextSize(13);
        root.addView(toolchainAction);

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        logView.setVisibility(View.GONE);
        root.addView(logView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(16), 0, 0);
        root.addView(buttons);

        Button cancel = new Button(this, null, 0);
        cancel.setText("Cancel");
        cancel.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cancel.setOnClickListener(v -> finish());
        buttons.addView(cancel);

        createBtn = new Button(this, null, 0);
        createBtn.setText("Create");
        createBtn.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        createBtn.setOnClickListener(v -> createProject());
        buttons.addView(createBtn);

        refreshToolchainRow();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void addLanguageRow(String title, String blurb, boolean checked) {
        RadioButton rb = new RadioButton(this);
        rb.setText(title + "\n" + blurb);
        rb.setTextSize(14);
        rb.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        rb.setChecked(checked);
        rb.setPadding(0, dp(6), 0, dp(6));
        languageGroup.addView(rb);
    }

    private SetupConfig.Language selectedLanguage() {
        int checkedId = languageGroup.getCheckedRadioButtonId();
        if (checkedId == -1) return null;
        View checked = languageGroup.findViewById(checkedId);
        int index = languageGroup.indexOfChild(checked);
        return languageAt(index);
    }

    private void refreshToolchainRow() {
        SetupConfig.Language lang = selectedLanguage();
        boolean runtimeReady = EmbeddedRuntime.isEmbeddedReady(this);
        if (lang == null) {
            toolchainStatus.setText("No toolchain needed for an empty project.");
            toolchainAction.setVisibility(View.GONE);
            createBtn.setEnabled(true);
            return;
        }
        boolean installed = prefs.getBoolean(SetupConfig.toolchainFlag(lang.pkg), false);
        if (!runtimeReady) {
            toolchainStatus.setText(lang.pkg + ": needs the Linux runtime first "
                + "(Settings → Linux Environment).");
            toolchainStatus.setTextColor(ContextCompat.getColor(this, R.color.warning));
            toolchainAction.setVisibility(View.GONE);
            boolean cppBlocked = lang == SetupConfig.Language.CPP
                && !ProjectManager.isCppEnabled();
            createBtn.setEnabled(!cppBlocked);
            if (cppBlocked) {
                toolchainStatus.setText(lang.pkg + ": C/C++ needs the Linux runtime — "
                    + "download it from Settings → Linux Environment first.");
            }
            return;
        }
        if (installed) {
            toolchainStatus.setText(lang.pkg + ": installed ✓");
            toolchainStatus.setTextColor(ContextCompat.getColor(this, R.color.success));
            toolchainAction.setVisibility(View.GONE);
        } else {
            toolchainStatus.setText(lang.pkg + ": not installed yet.");
            toolchainStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            toolchainAction.setVisibility(View.VISIBLE);
            toolchainAction.setText("Install " + lang.pkg);
            toolchainAction.setOnClickListener(v -> installToolchain(lang));
        }
        createBtn.setEnabled(true);
    }

    private void installToolchain(SetupConfig.Language lang) {
        toolchainAction.setEnabled(false);
        createBtn.setEnabled(false);
        logView.setVisibility(View.VISIBLE);
        logView.setText("$ pkg install -y " + lang.pkg + "\n");
        toolchainStatus.setText("Installing " + lang.pkg + "…");
        executor.execute(() -> {
            try {
                int exit = backend.executeCapture(
                    new ExecRequest(
                        Arrays.asList("pkg", "install", "-y", lang.pkg), null, false),
                    new EmbeddedLinuxBackend.OutputSink() {
                        @Override public void onStdout(byte[] data, int len) {
                            appendLog(new String(data, 0, len));
                        }
                        @Override public void onStderr(byte[] data, int len) {
                            appendLog(new String(data, 0, len));
                        }
                        @Override public void onExit(int exitCode) {
                        }
                    });
                final boolean ok = exit == 0;
                if (ok) {
                    prefs.edit().putBoolean(
                        SetupConfig.toolchainFlag(lang.pkg), true).apply();
                }
                runOnUiThread(() -> {
                    appendLog(ok ? "✓ " + lang.pkg + " installed\n"
                        : "✗ install failed (exit " + exit + ")\n");
                    toolchainAction.setEnabled(true);
                    refreshToolchainRow();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    appendLog("✗ " + ex.getMessage() + "\n");
                    toolchainAction.setEnabled(true);
                    refreshToolchainRow();
                });
            }
        });
    }

    private void appendLog(String text) {
        runOnUiThread(() -> logView.append(text));
    }

    private void createProject() {
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Project name cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }
        SetupConfig.Language lang = selectedLanguage();
        String entry = lang == null ? null : SetupConfig.entryFileName(lang);
        createBtn.setEnabled(false);
        projectManager.createProject(name, entry,
            new ProjectManager.OnProjectCreatedCallback() {
                @Override
                public void onCreated(Project project) {
                    Intent data = new Intent();
                    data.putExtra(EXTRA_PROJECT_PATH, project.getPath());
                    data.putExtra(EXTRA_ENTRY_NAME, entry);
                    setResult(RESULT_OK, data);
                    finish();
                }
                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        createBtn.setEnabled(true);
                        Toast.makeText(ProjectCreatorActivity.this, error,
                            Toast.LENGTH_LONG).show();
                    });
                }
            });
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        label.setTextSize(12);
        label.setPadding(0, dp(14), 0, dp(4));
        return label;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
