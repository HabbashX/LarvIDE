package com.larv.ide.ui.dialog;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
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

/**
 * IntelliJ-style settings dialog: search bar at the top, a category tree on
 * the left and the settings panel for the selected category on the right.
 * Tapping a category on the left swaps the right panel instantly.
 */
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

        // ------------------------------------------------------------------ root
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(ContextCompat.getColor(activity, R.color.background));

        // ---------------------------------------------------------------- search
        TextView searchLabel = new TextView(activity);
        searchLabel.setText("Search settings");
        searchLabel.setTextSize(12);
        searchLabel.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        searchLabel.setPadding(0, 0, 0, dp(activity, 4));
        root.addView(searchLabel);

        EditText searchField = new EditText(activity);
        searchField.setHint("Search...");
        searchField.setTextSize(14);
        searchField.setBackground(ContextCompat.getDrawable(activity, R.drawable.edittext_ide));
        searchField.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
        searchField.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        root.addView(searchField);

        // ---------------------------------------------------------- split pane
        LinearLayout split = new LinearLayout(activity);
        split.setOrientation(LinearLayout.HORIZONTAL);
        split.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        split.setPadding(0, dp(activity, 8), 0, 0);
        root.addView(split);

        // left category pane
        LinearLayout categories = new LinearLayout(activity);
        categories.setOrientation(LinearLayout.VERTICAL);
        categories.setLayoutParams(new LinearLayout.LayoutParams(
                dp(activity, 200), ViewGroup.LayoutParams.MATCH_PARENT));
        categories.setBackgroundColor(ContextCompat.getColor(activity, R.color.surface_variant));
        split.addView(categories);

        // right settings pane (scrollable)
        ScrollView settingsScroller = new ScrollView(activity);
        settingsScroller.setFillViewport(true);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rightLp.leftMargin = dp(activity, 8);
        settingsScroller.setLayoutParams(rightLp);
        split.addView(settingsScroller);

        LinearLayout settingsPane = new LinearLayout(activity);
        settingsPane.setOrientation(LinearLayout.VERTICAL);
        settingsPane.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 16));
        settingsScroller.addView(settingsPane);

        // state
        final String[] currentCat = {"appearance"};

        // ------------------------------------------------------------ categories
        String[][] catGroups = {
                {"appearance", "Appearance & Behavior"},
                {"editor", "Editor"},
                {"build", "Build, Execution, Deployment"},
                {"project", "Project"},
        };

        for (String[] group : catGroups) {
            TextView cat = categoryItem(activity, group[1], group[0].equals(currentCat[0]));
            cat.setTag(group[0]);
            cat.setOnClickListener(v -> {
                currentCat[0] = (String) v.getTag();
                refreshCategories(activity, categories, catGroups, currentCat[0]);
                buildSettingsPane(settingsPane, activity, prefs, callbacks,
                        currentCat[0], accent);
            });
            categories.addView(cat);
        }

        // ---------------------------------------------------------- build panes
        buildSettingsPane(settingsPane, activity, prefs, callbacks,
                currentCat[0], accent);

        // -------------------------------------------------------------- buttons
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(activity, 12), 0, 0);
        root.addView(buttons);

        android.widget.Button cancelBtn = new android.widget.Button(activity, null, 0);
        cancelBtn.setText("Cancel");
        cancelBtn.setTextSize(13);
        cancelBtn.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8));
        buttons.addView(cancelBtn);

        android.widget.Button applyBtn = new android.widget.Button(activity, null, 0);
        applyBtn.setText("Apply");
        applyBtn.setTextSize(13);
        applyBtn.setTextColor(ContextCompat.getColor(activity, R.color.text_on_primary));
        applyBtn.setBackgroundColor(ContextCompat.getColor(activity, R.color.ide_accent));
        applyBtn.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8));
        buttons.addView(applyBtn);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setCancelable(true)
                .create();

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        applyBtn.setOnClickListener(v -> {
            applySettings(activity, prefs, callbacks, settingsPane, currentCat[0], accent);
            dialog.dismiss();
        });

        dialog.show();
    }

    /* =========================================================================
     * Apply: read UI controls back into prefs, then fire the right callback
     * ========================================================================= */

    private static void applySettings(Activity activity,
                                      SharedPreferences prefs,
                                      Callbacks callbacks,
                                      LinearLayout pane,
                                      String category,
                                      int accent) {
        SharedPreferences.Editor ed = prefs.edit();

        Object tag = pane.getTag();
        boolean accentChanged = false;
        String newAccent = prefs.getString("appAccent", "blue");

        if (tag instanceof Object[] && category.equals("appearance")) {
            Object[] state = (Object[]) tag;
            Spinner accentSpinner = (Spinner) state[0];
            EditText customAccent = (EditText) state[1];
            Spinner themeSpinner = (Spinner) state[2];
            Spinner familySpinner = (Spinner) state[3];

            String[] accentKeys = {"blue", "purple", "green", "orange", "pink", "cyan", "custom"};
            newAccent = accentKeys[accentSpinner.getSelectedItemPosition()];
            String[] themeIds = (state.length > 4) ? (String[]) state[4]
                : new String[]{"islands-dark", "vscode-dark-plus", "monokai", "larv-light", "solarized-light"};
            String newTheme = themeIds[themeSpinner.getSelectedItemPosition()];
            String[] familyKeys = (state.length > 5) ? (String[]) state[5]
                : new String[]{"jetbrains", "fira", "roboto", "mono", "system"};
            String newFamily = familyKeys[familySpinner.getSelectedItemPosition()];

            ed.putString("appAccent", newAccent)
                .putString("editorTheme", newTheme)
                .putString("editorFontFamily", newFamily);

            if ("custom".equals(newAccent)) {
                String hex = customAccent.getText().toString().trim();
                try {
                    int color = android.graphics.Color.parseColor(hex);
                    ed.putInt("customAccentColor", color);
                } catch (IllegalArgumentException e) {
                    ed.remove("customAccentColor");
                }
            } else {
                ed.remove("customAccentColor");
            }

            accentChanged = !newAccent.equals(prefs.getString("appAccent", "blue"));
        } else if (tag instanceof Object[] && category.equals("editor")) {
            Object[] state = (Object[]) tag;
            SeekBar fontSeek = (SeekBar) state[0];
            Spinner tabSpinner = (Spinner) state[1];

            boolean hasNativeMin =
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O;
            int fontMin = 10;
            int newSize = hasNativeMin ? fontSeek.getProgress()
                : fontSeek.getProgress() + fontMin;
            int newTabSize = Integer.parseInt((String) tabSpinner.getSelectedItem());

            ed.putInt("editorFontSize", newSize)
                .putInt("editorTabSize", newTabSize);

            // re-apply editor live
            callbacks.onEditorSettingsApplied(
                prefs.getString("editorTheme", "islands-dark"),
                prefs.getString("editorFontFamily", "jetbrains"),
                newSize, newTabSize);
        } else if (tag instanceof Object[] && category.equals("build")) {
            Object[] state = (Object[]) tag;
            Spinner levelSpinner = (Spinner) state[0];
            ed.putString("javaLevel", (String) levelSpinner.getSelectedItem());
        }

        ed.apply();

        if (accentChanged) {
            callbacks.onAccentChanged(newAccent);
        }
    }

    /* =========================================================================
     * Category tree
     * ========================================================================= */

    private static TextView categoryItem(Activity activity, String text, boolean selected) {
        TextView item = new TextView(activity);
        item.setText(text);
        item.setTextSize(13);
        item.setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 10), dp(activity, 10));
        item.setTextColor(selected
                ? ContextCompat.getColor(activity, R.color.ide_accent)
                : ContextCompat.getColor(activity, R.color.text_primary));
        item.setBackgroundColor(selected
                ? ContextCompat.getColor(activity, R.color.primary_light)
                : android.graphics.Color.TRANSPARENT);
        return item;
    }

    private static void refreshCategories(Activity activity,
                                          LinearLayout categories,
                                          String[][] catGroups,
                                          String selected) {
        categories.removeAllViews();
        for (String[] group : catGroups) {
            TextView cat = categoryItem(activity, group[1], group[0].equals(selected));
            cat.setTag(group[0]);
            categories.addView(cat);
        }
    }

    /* =========================================================================
     * Settings panes
     * ========================================================================= */

    private static void buildSettingsPane(LinearLayout pane,
                                          Activity activity,
                                          SharedPreferences prefs,
                                          Callbacks callbacks,
                                          String category,
                                          int accent) {
        pane.removeAllViews();

        if ("appearance".equals(category)) {
            buildAppearancePane(pane, activity, prefs, callbacks, accent);
        } else if ("editor".equals(category)) {
            buildEditorPane(pane, activity, prefs, callbacks, accent);
        } else if ("build".equals(category)) {
            buildBuildPane(pane, activity, prefs, callbacks, accent);
        } else if ("project".equals(category)) {
            buildProjectPane(pane, activity, prefs, callbacks);
        }
    }

    // ---- Appearance & Behavior ----------------------------------------------

    private static void buildAppearancePane(LinearLayout pane,
                                            Activity activity,
                                            SharedPreferences prefs,
                                            Callbacks callbacks,
                                            int accent) {
        pane.addView(sectionTitle(activity, "Appearance"));

        final String savedAccent = prefs.getString("appAccent", "blue");
        final String[] accentKeys = {"blue", "purple", "green", "orange", "pink", "cyan", "custom"};
        final String[] accentNames = {"Blue", "Purple", "Green", "Orange", "Pink", "Cyan", "Custom"};
        pane.addView(label(activity, "Accent color"));
        final Spinner accentSpinner = spinner(activity, accentNames,
                Math.max(0, java.util.Arrays.asList(accentKeys).indexOf(savedAccent)));
        pane.addView(accentSpinner);

        final EditText customAccent = new EditText(activity);
        customAccent.setHint("#RRGGBB");
        customAccent.setText(prefs.getString("customAccentColor", ""));
        customAccent.setTextSize(13);
        customAccent.setBackground(ContextCompat.getDrawable(activity, R.drawable.edittext_ide));
        customAccent.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));
        customAccent.setVisibility("custom".equals(savedAccent)
                ? View.VISIBLE : View.GONE);
        pane.addView(customAccent);
        accentSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View v,
                                                 int position, long id) {
                customAccent.setVisibility("custom".equals(accentKeys[position])
                        ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        pane.addView(divider(activity));
        pane.addView(sectionTitle(activity, "Theme"));
        final String savedTheme = prefs.getString("editorTheme", "islands-dark");
        final String[] themeIds = {"islands-dark", "vscode-dark-plus", "monokai",
                "larv-light", "solarized-light"};
        final String[] themeNames = {"Islands Dark", "VS Dark+", "Monokai",
                "Light", "Solarized Light"};
        final Spinner themeSpinner = spinner(activity, themeNames,
                java.util.Arrays.asList(themeIds).indexOf(savedTheme));
        pane.addView(themeSpinner);

        pane.addView(divider(activity));
        pane.addView(sectionTitle(activity, "Font"));
        final String savedFamily = prefs.getString("editorFontFamily", "jetbrains");
        final String[] familyKeys = {"jetbrains", "fira", "roboto", "mono", "system"};
        final String[] familyNames = {"JetBrains Mono", "Fira Code", "Roboto Mono",
                "Monospace", "System sans-serif"};
        final Spinner familySpinner = spinner(activity, familyNames,
                java.util.Arrays.asList(familyKeys).indexOf(savedFamily));
        pane.addView(familySpinner);

        pane.setTag(new Object[]{accentSpinner, customAccent, themeSpinner, familySpinner,
                themeIds, familyKeys});
    }

    // ---- Editor -------------------------------------------------------------

    private static void buildEditorPane(LinearLayout pane,
                                        Activity activity,
                                        SharedPreferences prefs,
                                        Callbacks callbacks,
                                        int accent) {
        pane.addView(sectionTitle(activity, "General"));
        pane.addView(toggle(activity, accent, "Word wrap",
                prefs.getBoolean("editorWordWrap", false),
                (b, checked) -> prefs.edit().putBoolean("editorWordWrap", checked).apply()));
        pane.addView(toggle(activity, accent, "Show line numbers",
                prefs.getBoolean("editorLineNumbers", true),
                (b, checked) -> prefs.edit().putBoolean("editorLineNumbers", checked).apply()));
        pane.addView(toggle(activity, accent, "Minimap",
                prefs.getBoolean("editorMinimap", false),
                (b, checked) -> prefs.edit().putBoolean("editorMinimap", checked).apply()));
        pane.addView(toggle(activity, accent, "Indent guides",
                prefs.getBoolean("editorIndentGuides", true),
                (b, checked) -> prefs.edit().putBoolean("editorIndentGuides", checked).apply()));
        pane.addView(toggle(activity, accent, "Highlight current line",
                prefs.getBoolean("editorHighlightLine", true),
                (b, checked) -> prefs.edit().putBoolean("editorHighlightLine", checked).apply()));
        pane.addView(toggle(activity, accent, "Auto save",
                prefs.getBoolean("autosaveEnabled", true),
                (b, checked) -> prefs.edit().putBoolean("autosaveEnabled", checked).apply()));

        pane.addView(divider(activity));
        pane.addView(sectionTitle(activity, "Font Size"));
        final int savedFont = prefs.getInt("editorFontSize", 14);
        final boolean hasNativeMin =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O;
        final int fontMin = 10;
        final int fontMax = 24;
        final SeekBar fontSeek = new SeekBar(activity);
        if (hasNativeMin) {
            fontSeek.setMin(fontMin);
            fontSeek.setMax(fontMax);
            fontSeek.setProgress(Math.min(fontMax, Math.max(fontMin, savedFont)));
        } else {
            fontSeek.setMax(fontMax - fontMin);
            fontSeek.setProgress(Math.min(fontMax - fontMin, Math.max(0, savedFont - fontMin)));
        }
        fontSeek.getProgressDrawable().setColorFilter(
                accent, PorterDuff.Mode.SRC_IN);
        fontSeek.getThumb().setColorFilter(
                accent, PorterDuff.Mode.SRC_IN);
        pane.addView(fontSeek);

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
        pane.addView(fontValue);

        pane.addView(divider(activity));
        pane.addView(sectionTitle(activity, "Tab Size"));
        final String[] tabSizes = {"2", "4", "8"};
        final int savedTab = prefs.getInt("editorTabSize", 4);
        final Spinner tabSpinner = spinner(activity, tabSizes,
                Math.max(0, java.util.Arrays.asList(tabSizes).indexOf(String.valueOf(savedTab))));
        pane.addView(tabSpinner);

        pane.setTag(new Object[]{fontSeek, tabSpinner, prefs});
    }

    // ---- Build, Execution, Deployment ---------------------------------------

    private static void buildBuildPane(LinearLayout pane,
                                       Activity activity,
                                       SharedPreferences prefs,
                                       Callbacks callbacks,
                                       int accent) {
        pane.addView(sectionTitle(activity, "Java"));
        pane.addView(label(activity, "Java language level"));
        final String[] levels = {"11", "16", "17"};
        final String savedLevel = prefs.getString("javaLevel", "16");
        final Spinner levelSpinner = spinner(activity, levels,
                Math.max(0, java.util.Arrays.asList(levels).indexOf(savedLevel)));
        pane.addView(levelSpinner);

        pane.addView(divider(activity));
        pane.addView(sectionTitle(activity, "Linux Environment"));
        pane.addView(linuxEnvironmentSection(activity, prefs));

        pane.setTag(new Object[]{levelSpinner, prefs});
    }

    // ---- Project ------------------------------------------------------------

    private static void buildProjectPane(LinearLayout pane,
                                         Activity activity,
                                         SharedPreferences prefs,
                                         Callbacks callbacks) {
        pane.addView(sectionTitle(activity, "Project"));
        pane.addView(label(activity, "No project-specific settings available yet."));
        pane.addView(toggle(activity, callbacks.accentColor(), "Show hidden files",
                prefs.getBoolean("projectShowHidden", false),
                (b, checked) -> prefs.edit().putBoolean("projectShowHidden", checked).apply()));
    }

    /* =========================================================================
     * Shared UI helpers
     * ========================================================================= */

    private static TextView sectionTitle(Activity activity, String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(13);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        label.setPadding(0, dp(activity, 14), 0, dp(activity, 6));
        return label;
    }

    private static TextView label(Activity activity, String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        label.setTextSize(12);
        label.setPadding(0, dp(activity, 8), 0, dp(activity, 4));
        return label;
    }

    private static View divider(Activity activity) {
        View div = new View(activity);
        div.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        div.setBackgroundColor(ContextCompat.getColor(activity, R.color.divider));
        return div;
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

    /* ----------------------------------------------------------------------- */
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

        runBackground(() -> {
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
        });

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

        downloadBtn.setOnClickListener(v -> {
            downloadBtn.setEnabled(false);
            progress.setVisibility(android.view.View.VISIBLE);
            progress.setIndeterminate(true);
            status.setText("Downloading…");
            status.setTextColor(ContextCompat.getColor(activity, R.color.warning));
            installer.installAsync(null,
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
            runBackground(() -> {
                deleteRecursive(new java.io.File(activity.getFilesDir(), "usr"));
                activity.runOnUiThread(() -> {
                    com.larv.ide.project.ProjectManager.setCppEnabled(false);
                    removeBtn.setEnabled(true);
                    removeBtn.setVisibility(android.view.View.GONE);
                    downloadBtn.setText("Download");
                    refreshInfo.run();
                    detail.setText("Removed. Re-download anytime to restore C/C++.");
                });
            });
        });

        return section;
    }

    private static void runBackground(Runnable task) {
        try {
            new com.larv.ide.concurrent.IOExecutor(1).execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
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
        f.delete();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
