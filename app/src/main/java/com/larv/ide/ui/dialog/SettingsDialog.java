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


}
