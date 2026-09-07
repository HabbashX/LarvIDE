package com.larv.ide.ui.setup;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.larv.ide.R;
import com.larv.ide.project.ProjectManager;
import com.larv.ide.run.backend.ExecRequest;
import com.larv.ide.run.backend.embedded.EmbeddedLinuxBackend;
import com.larv.ide.run.backend.embedded.EmbeddedRuntime;
import com.larv.ide.run.backend.embedded.PrefixInstaller;
import com.larv.ide.setup.SetupConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * First-launch setup: pick languages → download Linux runtime → install the
 * matching toolchains. Skippable at every step ("Later"); re-runnable from
 * Settings → Linux Environment. Language-pack fetch plugs into
 * {@link #fetchLanguagePacks(List)} (step 7 of the roadmap).
 */
public class SetupWizardActivity extends AppCompatActivity {

    private static final String PREFS = "larv_ide";

    private int step = 0;
    private LinearLayout stepHost;
    private TextView stepTitle;

    private final List<CheckBox> languageBoxes = new ArrayList<>();
    private EmbeddedLinuxBackend backend;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Theme must match MainActivity accent handling (default blue is fine here).
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        backend = new EmbeddedLinuxBackend(getApplicationContext());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(16), pad, dp(16));
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.background));

        TextView brand = new TextView(this);
        brand.setText("LarvIDE setup");
        brand.setTextSize(22);
        brand.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(brand);

        stepTitle = new TextView(this);
        stepTitle.setTextSize(13);
        stepTitle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        stepTitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(stepTitle);

        stepHost = new LinearLayout(this);
        stepHost.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        stepHost.setLayoutParams(hostLp);
        root.addView(stepHost);

        setContentView(root);
        showStep(0);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ steps

    private void showStep(int s) {
        step = s;
        stepHost.removeAllViews();
        languageBoxes.clear();
        if (s == 0) {
            stepTitle.setText("Step 1 of 3 — what do you work in?");
            buildLanguageStep();
        } else if (s == 1) {
            stepTitle.setText("Step 2 of 3 — Linux runtime (one-time download)");
            buildRuntimeStep();
        } else {
            stepTitle.setText("Step 3 of 3 — install your toolchains");
            buildToolchainStep();
        }
    }

    private void buildLanguageStep() {
        TextView hint = new TextView(this);
        hint.setText("LarvIDE installs only what you need. You can change this later in Settings.");
        hint.setTextSize(13);
        hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        hint.setPadding(0, 0, 0, dp(8));
        stepHost.addView(hint);

        List<SetupConfig.Language> stored = SetupConfig.parseStored(
            prefs.getStringSet(SetupConfig.PREF_SETUP_LANGUAGES, null));
        for (SetupConfig.Language lang : SetupConfig.Language.values()) {
            CheckBox box = new CheckBox(this);
            box.setText(lang.title + "\n" + lang.blurb);
            box.setTextSize(14);
            box.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            box.setChecked(stored.isEmpty() || stored.contains(lang));
            box.setPadding(0, dp(6), 0, dp(6));
            stepHost.addView(box);
            languageBoxes.add(box);
        }

        stepHost.addView(buttonRow(
            navButton("Later", v -> finish()),
            navButton("Continue", v -> {
                persistSelection();
                showStep(1);
            })));
    }

    private void buildRuntimeStep() {
        TextView info = new TextView(this);
        info.setTextSize(13);
        info.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        stepHost.addView(info);

        ProgressBar progress = new ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        stepHost.addView(progress);

        Button action = navButton("Download (~100–150 MB, WiFi recommended)", null);
        stepHost.addView(action);
        stepHost.addView(buttonRow(
            navButton("Back", v -> showStep(0)),
            navButton("Skip", v -> showStep(2))));

        Runnable refresh = () -> {
            boolean ready = EmbeddedRuntime.isEmbeddedReady(this);
            boolean installing = backend.installer().isInstalling();
            if (ready) {
                info.setText("Linux runtime installed — ready.");
                info.setTextColor(ContextCompat.getColor(this, R.color.success));
                action.setText("Continue");
                action.setEnabled(true);
                action.setOnClickListener(v -> showStep(2));
            } else if (installing) {
                info.setText("Downloading…");
                action.setEnabled(false);
            } else if (!EmbeddedRuntime.isArm64()) {
                info.setText("Embedded Linux needs arm64 — this device is not supported.");
                info.setTextColor(ContextCompat.getColor(this, R.color.error));
                action.setEnabled(false);
            } else {
                info.setText("Runs Java, C/C++, Python and Node inside LarvIDE — no other app needed.");
                action.setEnabled(true);
            }
        };
        refresh.run();

        action.setOnClickListener(v -> {
            action.setEnabled(false);
            progress.setVisibility(View.VISIBLE);
            progress.setIndeterminate(true);
            info.setText("Downloading…");
            backend.installer().installAsync(null, new PrefixInstaller.Listener() {
                @Override public void onProgress(String stage, int percent) {
                    info.setText(stage);
                    if (percent > 0) {
                        progress.setIndeterminate(false);
                        progress.setProgress(Math.min(100, percent));
                    }
                }
                @Override public void onComplete() {
                    ProjectManager.setCppEnabled(true);
                    Toast.makeText(SetupWizardActivity.this,
                        "Linux runtime ready", Toast.LENGTH_SHORT).show();
                    refresh.run();
                    progress.setVisibility(View.GONE);
                }
                @Override public void onError(String message) {
                    info.setText("Download failed: " + message);
                    info.setTextColor(ContextCompat.getColor(SetupWizardActivity.this,
                        R.color.error));
                    action.setEnabled(true);
                    progress.setVisibility(View.GONE);
                }
            });
        });
    }

    private void buildToolchainStep() {
        List<SetupConfig.Language> selected = selectedLanguages();
        Map<SetupConfig.Language, String> pkgs = SetupConfig.packagesFor(selected);

        ScrollView scroller = new ScrollView(this);
        TextView log = new TextView(this);
        log.setTextSize(12);
        log.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        scroller.addView(log);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scroller.setLayoutParams(logLp);
        stepHost.addView(scroller);

        Button install = navButton(
            pkgs.isEmpty() ? "Nothing selected" : "Install " + pkgs.size() + " package(s)", null);
        install.setEnabled(!pkgs.isEmpty() && EmbeddedRuntime.isEmbeddedReady(this));
        stepHost.addView(install);
        if (!EmbeddedRuntime.isEmbeddedReady(this)) {
            log.setText("Linux runtime is not installed — go Back and download it first,\n"
                + "or Finish and install toolchains later from Languages.");
        }

        stepHost.addView(buttonRow(
            navButton("Back", v -> showStep(1)),
            navButton("Finish", v -> {
                completeSetup();
            })));

        install.setOnClickListener(v -> {
            install.setEnabled(false);
            log.setText("");
            executor.execute(() -> {
                for (Map.Entry<SetupConfig.Language, String> e : pkgs.entrySet()) {
                    String pkg = e.getValue();
                    appendLog(log, "$ pkg install -y " + pkg + "\n");
                    try {
                        int exit = backend.executeCapture(
                            new ExecRequest(
                                Arrays.asList("pkg", "install", "-y", pkg), null, false),
                            new EmbeddedLinuxBackend.OutputSink() {
                                @Override public void onStdout(byte[] data, int len) {
                                    appendLog(log, new String(data, 0, len));
                                }
                                @Override public void onStderr(byte[] data, int len) {
                                    appendLog(log, new String(data, 0, len));
                                }
                                @Override public void onExit(int exitCode) {
                                }
                            });
                        if (exit == 0) {
                            prefs.edit().putBoolean(
                                SetupConfig.toolchainFlag(pkg), true).apply();
                        }
                        appendLog(log, (exit == 0 ? "✓ " : "✗ (exit " + exit + ") ")
                            + pkg + "\n\n");
                    } catch (Exception ex) {
                        appendLog(log, "✗ " + pkg + ": " + ex.getMessage() + "\n\n");
                    }
                }
                runOnUiThread(() -> {
                    appendLog(log, "Done. You can re-install anytime from Languages.\n");
                    fetchLanguagePacks(selected);
                });
            });
        });
    }

    // ---------------------------------------------------------------- helpers

    private List<SetupConfig.Language> selectedLanguages() {
        if (!languageBoxes.isEmpty()) {
            List<SetupConfig.Language> out = new ArrayList<>();
            SetupConfig.Language[] values = SetupConfig.Language.values();
            for (int i = 0; i < languageBoxes.size() && i < values.length; i++) {
                if (languageBoxes.get(i).isChecked()) out.add(values[i]);
            }
            return out;
        }
        return SetupConfig.parseStored(
            prefs.getStringSet(SetupConfig.PREF_SETUP_LANGUAGES, null));
    }

    private void persistSelection() {
        prefs.edit().putStringSet(SetupConfig.PREF_SETUP_LANGUAGES,
            SetupConfig.storeNames(selectedLanguages())).apply();
    }

    private void completeSetup() {
        persistSelection();
        prefs.edit().putBoolean(SetupConfig.PREF_SETUP_COMPLETE, true).apply();
        // C++ templates unlock exactly when the runtime is usable.
        ProjectManager.setCppEnabled(EmbeddedRuntime.isEmbeddedReady(this));
        Toast.makeText(this, "Setup complete — happy coding!", Toast.LENGTH_SHORT).show();
        finish();
    }

    /** Step 7 hook: fetch Monaco language packs for the chosen languages. */
    private void fetchLanguagePacks(List<SetupConfig.Language> selected) {
        // TODO(step7): download pack JSON per language into files/langpacks/
        // and register via window.registerLanguagePack. Core 8 stay baked in.
    }

    private void appendLog(TextView log, String text) {
        runOnUiThread(() -> log.append(text));
    }

    private Button navButton(String text, View.OnClickListener l) {
        Button b = new Button(this, null, 0);
        b.setText(text);
        b.setTextSize(13);
        if (l != null) b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.gravity = Gravity.CENTER_VERTICAL;
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout buttonRow(View... views) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);
        for (View v : views) row.addView(v);
        return row;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
