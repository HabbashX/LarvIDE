package com.larv.ide.ui.dialog;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.larv.ide.R;

public class SettingsDialog {

    public interface Callbacks {
        SharedPreferences prefs();
        int accentColor();
        void onAccentChanged(String newAccent);
        void onEditorSettingsApplied(String themeId, String fontFamily,
                                     int fontSize, int tabSize);
    }

    public static void show(Activity activity, @NonNull Callbacks callbacks) {
        SharedPreferences prefs = callbacks.prefs();
        final int accent = callbacks.accentColor();

        ScrollView scroller = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 24);
        root.setPadding(pad, dp(activity, 8), pad, dp(activity, 8));
        scroller.addView(root);

        final String savedAccent = prefs.getString("appAccent", "blue");
        final String[] accentKeys = {"blue", "purple", "green", "orange", "pink", "cyan"};
        final String[] accentNames = {"Blue", "Purple", "Green", "Orange", "Pink", "Cyan"};
        root.addView(label(activity, "Accent color"));
        final Spinner accentSpinner = spinner(activity, accentNames,
                java.util.Arrays.asList(accentNames).indexOf(savedAccent));
        root.addView(accentSpinner);

        root.addView(label(activity, "Editor theme", true));
        final String savedTheme = prefs.getString("editorTheme", "islands-dark");
        final String[] themeIds = {"islands-dark", "vscode-dark-plus", "monokai",
                "larv-light", "solarized-light"};
        final String[] themeNames = {"Islands Dark", "VS Dark+", "Monokai",
                "Light", "Solarized Light"};
        final Spinner themeSpinner = spinner(activity, themeNames,
                java.util.Arrays.asList(themeIds).indexOf(savedTheme));
        root.addView(themeSpinner);

        root.addView(label(activity, "Font family", true));
        final String savedFamily = prefs.getString("editorFontFamily", "jetbrains");
        final String[] familyKeys = {"jetbrains", "fira", "roboto", "mono", "system"};
        final String[] familyNames = {"JetBrains Mono", "Fira Code", "Roboto Mono",
                "Monospace", "System sans-serif"};
        final Spinner familySpinner = spinner(activity, familyNames,
                java.util.Arrays.asList(familyKeys).indexOf(savedFamily));
        root.addView(familySpinner);

        root.addView(label(activity, "Editor font size", true));
        final SeekBar fontSeek = new SeekBar(activity);
        final int savedFont = prefs.getInt("editorFontSize", 14);
        fontSeek.setMin(10);
        fontSeek.setMax(24);
        fontSeek.setProgress(savedFont);
        fontSeek.getProgressDrawable().setColorFilter(
                callbacks.accentColor(), PorterDuff.Mode.SRC_IN);
        fontSeek.getThumb().setColorFilter(
                callbacks.accentColor(), PorterDuff.Mode.SRC_IN);
        root.addView(fontSeek);

        final TextView fontValue = new TextView(activity);
        fontValue.setText(savedFont + " pt");
        fontValue.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        fontValue.setTextSize(13);
        fontSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                fontValue.setText(value + " pt");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        root.addView(fontValue);

        root.addView(toggle(activity, accent, "Word wrap",
                prefs.getBoolean("editorWordWrap", false),
                (b, checked) -> prefs.edit().putBoolean("editorWordWrap", checked).apply()));
        root.addView(toggle(activity, accent, "Show line numbers",
                prefs.getBoolean("editorLineNumbers", true),
                (b, checked) -> prefs.edit().putBoolean("editorLineNumbers", checked).apply()));
        root.addView(toggle(activity, accent, "Minimap",
                prefs.getBoolean("editorMinimap", false),
                (b, checked) -> prefs.edit().putBoolean("editorMinimap", checked).apply()));
        root.addView(toggle(activity, accent, "Indent guides",
                prefs.getBoolean("editorIndentGuides", true),
                (b, checked) -> prefs.edit().putBoolean("editorIndentGuides", checked).apply()));
        root.addView(toggle(activity, accent, "Highlight current line",
                prefs.getBoolean("editorHighlightLine", true),
                (b, checked) -> prefs.edit().putBoolean("editorHighlightLine", checked).apply()));
        root.addView(toggle(activity, accent, "Auto save",
                prefs.getBoolean("autosaveEnabled", true),
                (b, checked) -> prefs.edit().putBoolean("autosaveEnabled", checked).apply()));
        root.addView(toggle(activity, accent, "Run Java/C++ via embedded Linux",
                prefs.getBoolean("runViaEmbedded", true),
                (b, checked) -> prefs.edit().putBoolean("runViaEmbedded", checked).apply()));

        root.addView(linuxEnvironmentSection(activity, prefs));

        root.addView(label(activity, "Tab size", true));
        final String[] tabSizes = {"2", "4", "8"};
        final int savedTab = prefs.getInt("editorTabSize", 4);
        final Spinner tabSpinner = spinner(activity, tabSizes,
                Math.max(0, java.util.Arrays.asList(tabSizes).indexOf(String.valueOf(savedTab))));
        root.addView(tabSpinner);

        root.addView(label(activity, "Java language level", true));
        final String[] levels = {"11", "16", "17"};
        final String savedLevel = prefs.getString("javaLevel", "16");
        final Spinner levelSpinner = spinner(activity, levels,
                Math.max(0, java.util.Arrays.asList(levels).indexOf(savedLevel)));
        root.addView(levelSpinner);

        new AlertDialog.Builder(activity)
                .setTitle("Settings")
                .setView(scroller)
                .setPositiveButton("Apply", (dialog, which) -> {
                    String newAccent = accentKeys[accentSpinner.getSelectedItemPosition()];
                    String newTheme = themeIds[themeSpinner.getSelectedItemPosition()];
                    String newFamily = familyKeys[familySpinner.getSelectedItemPosition()];
                    int newSize = fontSeek.getProgress();
                    int newTabSize = Integer.parseInt(
                            (String) tabSpinner.getSelectedItem());
                    prefs.edit()
                            .putString("appAccent", newAccent)
                            .putString("editorTheme", newTheme)
                            .putString("editorFontFamily", newFamily)
                            .putInt("editorFontSize", newSize)
                            .putInt("editorTabSize", newTabSize)
                            .putString("javaLevel",
                                    (String) levelSpinner.getSelectedItem())
                            .apply();
                    if (!newAccent.equals(savedAccent)) {
                        callbacks.onAccentChanged(newAccent);
                    } else {
                        callbacks.onEditorSettingsApplied(newTheme, newFamily,
                                newSize, newTabSize);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static TextView label(Activity activity, String text) {
        return label(activity, text, false);
    }

    private static TextView label(Activity activity, String text, boolean spaced) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        label.setTextSize(12);
        label.setPadding(0, dp(activity, spaced ? 14 : 6), 0, dp(activity, 4));
        return label;
    }

    private static Spinner spinner(Activity activity, String[] items, int selection) {
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, selection));
        return spinner;
    }

    private static LinearLayout toggle(Activity activity, int accentColor, String labelText,
                                       boolean checked,
                                       CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(activity, 10), 0, dp(activity, 2));

        TextView text = new TextView(activity);
        text.setText(labelText);
        text.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        text.setTextSize(14);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text);

        SwitchCompat sw = new SwitchCompat(activity);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        sw.getTrackDrawable().setColorFilter(
                checked ? accentColor
                        : ContextCompat.getColor(activity, R.color.input_stroke),
                PorterDuff.Mode.SRC_IN);
        row.addView(sw);
        return row;
    }

    private static int dp(Activity activity, int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    /**
     * Linux Environment section: status, one-tap download with progress,
     * bootstrap URL override, and removal to free space. Runs entirely
     * in-app — no external Termux app involved.
     */
    private static LinearLayout linuxEnvironmentSection(Activity activity,
                                                        SharedPreferences prefs) {
        com.larv.ide.run.backend.embedded.PrefixInstaller installer =
            new com.larv.ide.run.backend.embedded.PrefixInstaller(activity);
        boolean ready =
            com.larv.ide.run.backend.embedded.EmbeddedRuntime.isEmbeddedReady(activity);
        boolean arm64 =
            com.larv.ide.run.backend.embedded.EmbeddedRuntime.isArm64();

        LinearLayout section = new LinearLayout(activity);
        section.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(activity);
        title.setText("Linux Environment");
        title.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        title.setTextSize(12);
        title.setPadding(0, dp(activity, 14), 0, dp(activity, 4));
        section.addView(title);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ContextCompat.getDrawable(activity, R.drawable.edittext_ide));
        card.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
        section.addView(card);

        TextView status = new TextView(activity);
        status.setTextSize(14);
        card.addView(status);

        TextView detail = new TextView(activity);
        detail.setTextSize(12);
        detail.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        detail.setPadding(0, dp(activity, 4), 0, 0);
        card.addView(detail);

        Runnable refreshInfo = () -> {
            boolean r =
                com.larv.ide.run.backend.embedded.EmbeddedRuntime.isEmbeddedReady(activity);
            if (installer.isInstalling()) {
                status.setText("Downloading…");
                status.setTextColor(ContextCompat.getColor(activity, R.color.warning));
            } else if (r) {
                status.setText("Installed — ready");
                status.setTextColor(ContextCompat.getColor(activity, R.color.success));
            } else {
                status.setText(arm64 ? "Not downloaded" : "Not supported on this device");
                status.setTextColor(ContextCompat.getColor(activity,
                    arm64 ? R.color.warning : R.color.error));
            }
        };
        refreshInfo.run();

        // Prefix size is computed off the UI thread (can be many files).
        new Thread(() -> {
            String info;
            if (com.larv.ide.run.backend.embedded.EmbeddedRuntime.isEmbeddedReady(activity)) {
                long bytes = dirSize(new java.io.File(activity.getFilesDir(), "usr"));
                info = "arm64 · " + formatSize(bytes) + " in private storage"
                    + "\nUnlocks: C/C++ (Clang), OpenJDK 17, Node.js, Rust";
            } else if (!arm64) {
                String abi = android.os.Build.SUPPORTED_ABIS.length > 0
                    ? android.os.Build.SUPPORTED_ABIS[0] : "unknown";
                info = "Requires arm64 — this device reports: " + abi;
            } else {
                info = "~100–150 MB one-time download (WiFi recommended)"
                    + "\nUnlocks: C/C++ (Clang), OpenJDK 17, Node.js, Rust";
            }
            final String text = info;
            activity.runOnUiThread(() -> detail.setText(text));
        }).start();

        android.widget.ProgressBar progress = new android.widget.ProgressBar(activity, null,
            android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressLp.topMargin = dp(activity, 8);
        progress.setLayoutParams(progressLp);
        card.addView(progress);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(activity, 8), 0, 0);
        card.addView(buttons);

        android.widget.Button downloadBtn = new android.widget.Button(activity, null, 0);
        downloadBtn.setText(ready ? "Re-download" : "Download");
        downloadBtn.setTextSize(12);
        downloadBtn.setEnabled(arm64 && !installer.isInstalling());
        buttons.addView(downloadBtn);

        android.widget.Button removeBtn = new android.widget.Button(activity, null, 0);
        removeBtn.setText("Remove");
        removeBtn.setTextSize(12);
        removeBtn.setVisibility(ready ? android.view.View.VISIBLE : android.view.View.GONE);
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        removeLp.leftMargin = dp(activity, 8);
        removeBtn.setLayoutParams(removeLp);
        buttons.addView(removeBtn);

        android.widget.EditText urlInput = new android.widget.EditText(activity);
        urlInput.setHint("Bootstrap URL override (optional)");
        urlInput.setText(prefs.getString("embeddedBootstrapUrl", ""));
        urlInput.setTextSize(12);
        urlInput.setSingleLine(true);
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        urlLp.topMargin = dp(activity, 8);
        urlInput.setLayoutParams(urlLp);
        card.addView(urlInput);
        urlInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                prefs.edit().putString("embeddedBootstrapUrl",
                    urlInput.getText().toString().trim()).apply();
            }
        });

        downloadBtn.setOnClickListener(v -> {
            String override = urlInput.getText().toString().trim();
            prefs.edit().putString("embeddedBootstrapUrl", override).apply();
            downloadBtn.setEnabled(false);
            progress.setVisibility(android.view.View.VISIBLE);
            progress.setIndeterminate(true);
            status.setText("Downloading…");
            status.setTextColor(ContextCompat.getColor(activity, R.color.warning));
            installer.installAsync(override.isEmpty() ? null : override,
                new com.larv.ide.run.backend.embedded.PrefixInstaller.Listener() {
                    @Override public void onProgress(String stage, int percent) {
                        status.setText(stage);
                        if (percent > 0) {
                            progress.setIndeterminate(false);
                            progress.setProgress(Math.min(100, percent));
                        }
                    }
                    @Override public void onComplete() {
                        com.larv.ide.project.ProjectManager.setCppEnabled(true);
                        downloadBtn.setEnabled(true);
                        downloadBtn.setText("Re-download");
                        removeBtn.setVisibility(android.view.View.VISIBLE);
                        progress.setVisibility(android.view.View.GONE);
                        refreshInfo.run();
                        detail.setText("Installed — C/C++ templates and Run unlocked.");
                    }
                    @Override public void onError(String message) {
                        downloadBtn.setEnabled(true);
                        progress.setVisibility(android.view.View.GONE);
                        status.setText("Download failed");
                        status.setTextColor(ContextCompat.getColor(activity, R.color.error));
                        detail.setText(message != null ? message : "Unknown error. Retry on WiFi.");
                    }
                });
        });

        removeBtn.setOnClickListener(v -> {
            removeBtn.setEnabled(false);
            status.setText("Removing…");
            new Thread(() -> {
                deleteRecursive(new java.io.File(activity.getFilesDir(), "usr"));
                activity.runOnUiThread(() -> {
                    com.larv.ide.project.ProjectManager.setCppEnabled(false);
                    removeBtn.setEnabled(true);
                    removeBtn.setVisibility(android.view.View.GONE);
                    downloadBtn.setText("Download");
                    refreshInfo.run();
                    detail.setText("Removed. Re-download anytime to restore C/C++.");
                });
            }).start();
        });

        return section;
    }

    private static long dirSize(java.io.File dir) {
        long total = 0;
        java.io.File[] files = dir.listFiles();
        if (files == null) return 0;
        for (java.io.File f : files) {
            total += f.isDirectory() ? dirSize(f) : f.length();
        }
        return total;
    }

    private static void deleteRecursive(java.io.File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            java.io.File[] children = f.listFiles();
            if (children != null) {
                for (java.io.File c : children) deleteRecursive(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

}
