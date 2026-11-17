package com.larv.ide.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        void onInspectionSettingsApplied();
        void onLanguageServerSettingsApplied();
    }

    /** One node of the IntelliJ-style settings tree. */
    private static final class NavNode {
        final String id;
        final String label;
        final String badge;
        final String keywords;
        final List<NavNode> children = new ArrayList<>();
        boolean expanded = true;

        NavNode(String id, String label, String badge, String keywords) {
            this.id = id;
            this.label = label;
            this.badge = badge;
            this.keywords = keywords;
        }

        NavNode add(NavNode child) {
            children.add(child);
            return this;
        }

        boolean isLeaf() {
            return children.isEmpty();
        }
    }

    private interface NavClick {
        void onClick(NavNode node);
    }

    public static void show(Activity activity, @NonNull Callbacks callbacks) {
        SharedPreferences prefs = callbacks.prefs();
        final int accent = callbacks.accentColor();
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        final boolean narrow = dm.widthPixels < dp(activity, 620);

        // IntelliJ's dialog is a fixed-size window with a title bar, search,
        // a navigation pane and a scrolling content pane. Using MATCH_PARENT
        // here is essential: the two panes share space via layout weights.
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(ContextCompat.getColor(activity, R.color.background));

        TextView title = new TextView(activity);
        title.setText("Settings");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        title.setBackgroundColor(ContextCompat.getColor(activity, R.color.surface_variant));
        title.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12));
        root.addView(title);

        EditText search = new EditText(activity);
        search.setHint("Search settings...");
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setBackground(ContextCompat.getDrawable(activity, R.drawable.edittext_ide));
        search.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.setMargins(dp(activity, 12), dp(activity, 10), dp(activity, 12), 0);
        search.setLayoutParams(searchLp);
        root.addView(search);

        LinearLayout split = new LinearLayout(activity);
        split.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        split.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        split.setPadding(0, dp(activity, 8), 0, 0);
        root.addView(split);

        // IntelliJ-style settings tree. Parents are expandable sections;
        // leaves are the actual settings pages.
        final List<NavNode> tree = new ArrayList<>();
        tree.add(new NavNode("overview_appearance", "Appearance & Behavior", "A",
            "appearance theme accent color font light dark")
            .add(new NavNode("appearance", "Appearance", "a",
                "theme accent color light dark custom")));
        tree.add(new NavNode("overview_editor", "Editor", "E",
            "editor font size tab wrap line numbers minimap indent guides autosave")
            .add(new NavNode("editor", "General", "g",
                "font size tab wrap line numbers minimap indent guides autosave"))
            .add(new NavNode("inspections", "Inspections", "i",
                "inspection warnings errors problems todo unused import whitespace code quality syntax"))
            .add(new NavNode("lsp", "Language Servers", "l",
                "language server LSP clangd pylsp jdtls typescript memory ram performance experimental")));
        tree.add(new NavNode("overview_build", "Build, Execution, Deployment", "B",
            "build java jdk language level linux termux runtime pkg terminal environment")
            .add(new NavNode("build", "Java Compiler", "j",
                "java jdk language level compiler")));
        tree.add(new NavNode("overview_project", "Project", "P", "project hidden files")
            .add(new NavNode("project", "Project Settings", "p", "project hidden files")));

        final List<NavNode> allNodes = new ArrayList<>();
        collectNodes(tree, allNodes);

        // Build every pane ONCE so unsaved edits are not lost when switching
        // pages (the old implementation rebuilt and discarded them).
        final Map<String, LinearLayout> panes = new LinkedHashMap<>();
        final Map<String, ScrollView> scrollers = new LinkedHashMap<>();
        FrameLayout content = new FrameLayout(activity);
        content.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        for (NavNode node : allNodes) {
            LinearLayout pane = new LinearLayout(activity);
            pane.setOrientation(LinearLayout.VERTICAL);
            pane.setPadding(dp(activity, 22), dp(activity, 12), dp(activity, 22), dp(activity, 20));
            buildSettingsPane(pane, activity, prefs, callbacks, node.id, accent);
            panes.put(node.id, pane);

            ScrollView scroller = new ScrollView(activity);
            scroller.setFillViewport(true);
            scroller.addView(pane);
            scrollers.put(node.id, scroller);
            content.addView(scroller);
        }

        LinearLayout navList = new LinearLayout(activity);
        navList.setOrientation(narrow ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        navList.setLayoutParams(new ViewGroup.LayoutParams(
            narrow ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        final View navHost;
        if (narrow) {
            HorizontalScrollView horizontal = new HorizontalScrollView(activity);
            horizontal.setHorizontalScrollBarEnabled(false);
            horizontal.addView(navList);
            horizontal.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 56)));
            navHost = horizontal;
        } else {
            ScrollView vertical = new ScrollView(activity);
            vertical.addView(navList);
            vertical.setBackgroundColor(ContextCompat.getColor(activity, R.color.surface_variant));
            vertical.setLayoutParams(new LinearLayout.LayoutParams(
                dp(activity, 250), ViewGroup.LayoutParams.MATCH_PARENT));
            navHost = vertical;
        }
        split.addView(navHost);
        split.addView(content);

        final String[] selected = {"appearance"};
        final Runnable[] renderNav = {null};
        renderNav[0] = () -> {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            boolean anyVisible = false;
            navList.removeAllViews();
            for (NavNode top : tree) {
                if (!query.isEmpty() && !matchesQuery(top, query)) continue;
                anyVisible = true;
                renderNode(activity, top, 0, query, selected[0], accent, navList,
                    node -> {
                        selected[0] = node.id;
                        if (!node.isLeaf()) node.expanded = !node.expanded;
                        renderNav[0].run();
                        showPane(scrollers, selected[0]);
                    });
            }
            if (!anyVisible) {
                TextView empty = new TextView(activity);
                empty.setText("No matching settings");
                empty.setTextSize(14);
                empty.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
                empty.setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16));
                navList.addView(empty);
            }
            showPane(scrollers, selected[0]);
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { renderNav[0].run(); }
        });
        renderNav[0].run();

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(activity, 10), 0, 0);
        root.addView(buttons);

        android.widget.Button cancelBtn = dialogButton(activity, "Cancel", accent, false);
        android.widget.Button applyBtn = dialogButton(activity, "Apply", accent, true);
        android.widget.Button okBtn = dialogButton(activity, "OK", accent, true);
        buttons.addView(cancelBtn);
        buttons.addView(applyBtn);
        buttons.addView(okBtn);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
            .setView(root)
            .setCancelable(true)
            .create();

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        applyBtn.setOnClickListener(v -> applySettings(activity, prefs, callbacks, panes));
        okBtn.setOnClickListener(v -> {
            applySettings(activity, prefs, callbacks, panes);
            dialog.dismiss();
        });

        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            int w = Math.min(dp(activity, 1080), (int) (dm.widthPixels * 0.96f));
            int h = Math.min(dp(activity, 780), (int) (dm.heightPixels * 0.92f));
            window.setLayout(w, h);
        }
    }

    private static void collectNodes(List<NavNode> nodes, List<NavNode> out) {
        for (NavNode node : nodes) {
            out.add(node);
            collectNodes(node.children, out);
        }
    }

    private static boolean matchesQuery(NavNode node, String query) {
        if (query.isEmpty()) return true;
        if ((node.label + " " + node.keywords).toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        for (NavNode child : node.children) {
            if (matchesQuery(child, query)) return true;
        }
        return false;
    }

    private static void renderNode(Activity activity, NavNode node, int depth, String query,
                                   String selected, int accent, ViewGroup host,
                                   NavClick click) {
        boolean hasQuery = !query.isEmpty();
        boolean isSelected = node.id.equals(selected);
        LinearLayout row = treeRow(activity, node, depth, isSelected,
            hasQuery || node.expanded, accent, hasQuery);
        row.setOnClickListener(v -> click.onClick(node));
        host.addView(row);
        if (hasQuery || node.expanded) {
            for (NavNode child : node.children) {
                if (!matchesQuery(child, query)) continue;
                renderNode(activity, child, depth + 1, query, selected, accent, host, click);
            }
        }
    }

    private static void showPane(Map<String, ScrollView> scrollers, String selected) {
        for (Map.Entry<String, ScrollView> entry : scrollers.entrySet()) {
            entry.getValue().setVisibility(
                entry.getKey().equals(selected) ? View.VISIBLE : View.GONE);
        }
    }

    private static android.widget.Button dialogButton(Activity activity, String text,
                                                       int accent, boolean primary) {
        android.widget.Button b = new android.widget.Button(activity, null, 0);
        b.setText(text);
        b.setTextSize(13);
        b.setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8));
        if (primary) {
            b.setTextColor(ContextCompat.getColor(activity, R.color.text_on_primary));
            b.setBackgroundColor(accent);
        }
        return b;
    }

    /* =========================================================================
     * Apply: read UI controls back into prefs, then fire the right callback
     * ========================================================================= */

    /**
     * Apply every category, not just the visible one. The panes are kept alive
     * for the whole dialog, so changes on one tab are never silently discarded
     * when the user taps another tab.
     */
    private static void applySettings(Activity activity,
                                      SharedPreferences prefs,
                                      Callbacks callbacks,
                                      Map<String, LinearLayout> panes) {
        SharedPreferences.Editor ed = prefs.edit();
        String oldAccent = prefs.getString("appAccent", "blue");
        String newAccent = oldAccent;

        LinearLayout appearance = panes.get("appearance");
        if (appearance != null && appearance.getTag() instanceof Object[]) {
            Object[] state = (Object[]) appearance.getTag();
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
                    ed.putInt("customAccentColor", android.graphics.Color.parseColor(hex));
                } catch (IllegalArgumentException e) {
                    ed.remove("customAccentColor");
                }
            } else {
                ed.remove("customAccentColor");
            }
        }

        int newFontSize = prefs.getInt("editorFontSize", 14);
        int newTabSize = prefs.getInt("editorTabSize", 4);
        LinearLayout editor = panes.get("editor");
        if (editor != null && editor.getTag() instanceof Object[]) {
            Object[] state = (Object[]) editor.getTag();
            SeekBar fontSeek = (SeekBar) state[0];
            Spinner tabSpinner = (Spinner) state[1];
            int fontMin = 10;
            boolean hasNativeMin =
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O;
            newFontSize = hasNativeMin ? fontSeek.getProgress()
                : fontSeek.getProgress() + fontMin;
            newTabSize = Integer.parseInt((String) tabSpinner.getSelectedItem());
            ed.putInt("editorFontSize", newFontSize).putInt("editorTabSize", newTabSize);
        }

        LinearLayout lspPane = panes.get("lsp");
        if (lspPane != null && lspPane.getTag() instanceof Object[]) {
            Object[] state = (Object[]) lspPane.getTag();
            @SuppressWarnings("unchecked")
            java.util.List<EditText> fields = (java.util.List<EditText>) state[0];
            @SuppressWarnings("unchecked")
            java.util.List<String> langList = (java.util.List<String>) state[1];
            for (int i = 0; i < fields.size() && i < langList.size(); i++) {
                ed.putString("lsp." + langList.get(i) + ".command",
                    fields.get(i).getText().toString().trim());
            }
        }

        LinearLayout inspections = panes.get("inspections");
        if (inspections != null && inspections.getTag() instanceof Object[]) {
            Object[] state = (Object[]) inspections.getTag();
            Spinner lengthSpinner = (Spinner) state[0];
            Spinner severitySpinner = (Spinner) state[1];
            String[] severityValues = (String[]) state[2];
            ed.putInt(com.larv.ide.infrastructure.analysis.InspectionConfig.PREF_MAX_LINE,
                Integer.parseInt((String) lengthSpinner.getSelectedItem()));
            ed.putString(com.larv.ide.infrastructure.analysis.InspectionConfig.PREF_MIN_SEVERITY,
                severityValues[severitySpinner.getSelectedItemPosition()]);
        }

        LinearLayout build = panes.get("build");
        if (build != null && build.getTag() instanceof Object[]) {
            Spinner levelSpinner = (Spinner) ((Object[]) build.getTag())[0];
            ed.putString("javaLevel", (String) levelSpinner.getSelectedItem());
        }

        ed.apply();
        callbacks.onInspectionSettingsApplied();
        callbacks.onLanguageServerSettingsApplied();

        if (editor != null) {
            callbacks.onEditorSettingsApplied(
                prefs.getString("editorTheme", "islands-dark"),
                prefs.getString("editorFontFamily", "jetbrains"),
                newFontSize, newTabSize);
        }
        if (!newAccent.equals(oldAccent)) callbacks.onAccentChanged(newAccent);
    }

    /* =========================================================================
     * Category tree
     * ========================================================================= */

    /** One row of the settings tree: disclosure arrow, badge and label. */
    private static LinearLayout treeRow(Activity activity, NavNode node, int depth,
                                        boolean selected, boolean expanded, int accent,
                                        boolean searching) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(activity, 42));
        row.setPadding(dp(activity, 8) + depth * dp(activity, 16), dp(activity, 10),
            dp(activity, 10), dp(activity, 10));
        row.setBackgroundColor(selected
            ? ContextCompat.getColor(activity, R.color.primary_light)
            : android.graphics.Color.TRANSPARENT);

        if (node.isLeaf()) {
            View spacer = new View(activity);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(activity, 14), 1));
            row.addView(spacer);
        } else {
            ImageView chevron = new ImageView(activity);
            chevron.setImageResource(R.drawable.ic_chevron_right);
            chevron.setColorFilter(ContextCompat.getColor(activity, R.color.text_secondary));
            chevron.setRotation(expanded && !searching ? 90f : 0f);
            chevron.setLayoutParams(new LinearLayout.LayoutParams(dp(activity, 14), dp(activity, 14)));
            row.addView(chevron);
        }

        TextView badge = new TextView(activity);
        badge.setText(node.badge);
        badge.setTextSize(12);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(ContextCompat.getColor(activity,
            selected ? R.color.text_on_primary : R.color.text_primary));
        badge.setBackgroundColor(selected ? accent
            : ContextCompat.getColor(activity, R.color.surface_variant));
        badge.setWidth(dp(activity, 24));
        badge.setHeight(dp(activity, 24));
        row.addView(badge);

        TextView label = new TextView(activity);
        label.setText(node.label);
        label.setTextSize(node.isLeaf() ? 14 : 15);
        if (!node.isLeaf()) label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setTextColor(selected
            ? ContextCompat.getColor(activity, R.color.ide_accent)
            : ContextCompat.getColor(activity, R.color.text_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(activity, 10);
        label.setLayoutParams(lp);
        row.addView(label);
        return row;
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

        if (category.startsWith("overview_")) {
            buildOverviewPane(pane, activity, category);
            return;
        }

        if ("appearance".equals(category)) {
            buildAppearancePane(pane, activity, prefs, callbacks, accent);
        } else if ("editor".equals(category)) {
            buildEditorPane(pane, activity, prefs, callbacks, accent);
        } else if ("inspections".equals(category)) {
            buildInspectionsPane(pane, activity, prefs, accent);
        } else if ("lsp".equals(category)) {
            buildLanguageServersPane(pane, activity, prefs, accent);
        } else if ("build".equals(category)) {
            buildBuildPane(pane, activity, prefs, callbacks, accent);
        } else if ("project".equals(category)) {
            buildProjectPane(pane, activity, prefs, callbacks);
        }
    }

    private static void buildOverviewPane(LinearLayout pane, Activity activity, String category) {
        String title;
        String body;
        switch (category) {
            case "overview_appearance":
                title = "Appearance & Behavior";
                body = "Choose the IDE theme, accent colour and editor font. "
                    + "Use the tree on the left to open Appearance.";
                break;
            case "overview_editor":
                title = "Editor";
                body = "Configure editing behaviour, code inspections and optional "
                    + "language servers. Expand Editor in the tree to open a page.";
                break;
            case "overview_build":
                title = "Build, Execution, Deployment";
                body = "Java language level and the embedded Linux runtime used to "
                    + "compile and run your projects.";
                break;
            default:
                title = "Project";
                body = "Project-wide options such as showing hidden files.";
                break;
        }
        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextSize(22);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        heading.setPadding(0, 0, 0, dp(activity, 12));
        pane.addView(heading);
        TextView text = new TextView(activity);
        text.setText(body);
        text.setTextSize(14);
        text.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        pane.addView(text);
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
        // Stored as an int by applySettings(); getString() would throw.
        int savedCustom = prefs.getInt("customAccentColor", 0);
        customAccent.setText(savedCustom == 0 ? ""
            : String.format(Locale.ROOT, "#%06X", savedCustom & 0xFFFFFF));
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

    // ---- Inspections --------------------------------------------------------

    private static void buildInspectionsPane(LinearLayout pane, Activity activity,
                                             SharedPreferences prefs, int accent) {
        String prefix = com.larv.ide.infrastructure.analysis.InspectionConfig.PREFIX;
        pane.addView(sectionTitle(activity, "Live analysis"));
        pane.addView(toggle(activity, accent, "Inspect while typing (700 ms debounce)",
            prefs.getBoolean(prefix + "live", true),
            (b, checked) -> {
                prefs.edit().putBoolean(prefix + "live", checked).apply();
                if (!checked) {
                    // Clearing markers is handled by MainActivity on the next edit.
                }
            }));

        pane.addView(divider(activity));
        pane.addView(sectionTitle(activity, "Enabled inspections"));
        String[][] checks = {
            {prefix + "syntax", "Syntax errors: braces, strings, ';', ':' (error)"},
            {prefix + "unusedImports", "Unused imports (warning)"},
            {prefix + "trailingWhitespace", "Trailing whitespace (weak warning)"},
            {prefix + "longLines", "Long lines (weak warning)"},
            {prefix + "todoComments", "TODO / FIXME comments (info)"},
            {prefix + "debugStatements", "Debug output left in source (info)"},
            {prefix + "emptyCatch", "Empty catch blocks (warning)"},
            {prefix + "stringComparison", "String == comparison (warning)"},
            {prefix + "tabs", "Tab characters (weak warning)"},
        };
        boolean[] defaults = {true, true, true, true, true, true, true, true, false};
        for (int i = 0; i < checks.length; i++) {
            final String key = checks[i][0];
            pane.addView(toggle(activity, accent, checks[i][1],
                prefs.getBoolean(key, defaults[i]),
                (b, checked) -> prefs.edit().putBoolean(key, checked).apply()));
        }

        pane.addView(divider(activity));
        pane.addView(sectionTitle(activity, "Thresholds"));
        pane.addView(label(activity, "Maximum line length"));
        String[] lengths = {"80", "100", "120", "150"};
        int savedLength = prefs.getInt(prefix + "maxLineLength", 120);
        Spinner lengthSpinner = spinner(activity, lengths,
            Math.max(0, java.util.Arrays.asList(lengths).indexOf(String.valueOf(savedLength))));
        pane.addView(lengthSpinner);

        pane.addView(label(activity, "Show severities"));
        String[] severities = {"All", "Weak warnings and above", "Warnings and above", "Errors only"};
        String[] severityValues = {"INFO", "WEAK_WARNING", "WARNING", "ERROR"};
        String savedSeverity = prefs.getString(prefix + "minSeverity", "WEAK_WARNING");
        int severityIndex = java.util.Arrays.asList(severityValues).indexOf(savedSeverity);
        Spinner severitySpinner = spinner(activity, severities, Math.max(0, severityIndex));
        pane.addView(severitySpinner);

        pane.setTag(new Object[]{lengthSpinner, severitySpinner, severityValues});
    }

    // ---- Language servers (opt-in) ------------------------------------------

    private static void buildLanguageServersPane(LinearLayout pane, Activity activity,
                                                 SharedPreferences prefs, int accent) {
        TextView warning = new TextView(activity);
        warning.setText("EXPERIMENTAL — language servers are separate processes that can use "
            + "100–400 MB of RAM each (jdtls needs a full JVM). On a 2 GB device this can "
            + "slow the editor down, drain the battery or trigger Android's low-memory killer. "
            + "Only enable one you actually need, and install its package first.");
        warning.setTextSize(12);
        warning.setTextColor(ContextCompat.getColor(activity, R.color.warning));
        warning.setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
        warning.setBackgroundColor(ContextCompat.getColor(activity, R.color.surface_variant));
        pane.addView(warning);

        final String[] langs = com.larv.ide.infrastructure.analysis.LanguageServerManager.LANGS;
        final String[] titles = com.larv.ide.infrastructure.analysis.LanguageServerManager.TITLES;
        final String[] defaults =
            com.larv.ide.infrastructure.analysis.LanguageServerManager.DEFAULT_COMMANDS;
        final String[] packages =
            com.larv.ide.infrastructure.analysis.LanguageServerManager.PACKAGES;

        final java.util.List<EditText> commandFields = new java.util.ArrayList<>();
        final java.util.List<String> commandLangs = new java.util.ArrayList<>();
        for (int i = 0; i < langs.length; i++) {
            final String lang = langs[i];
            final String key = "lsp." + lang + ".enabled";
            pane.addView(sectionTitle(activity, titles[i]));
            pane.addView(label(activity, "Requires Termux package: " + packages[i]));
            pane.addView(toggle(activity, accent, "Enable " + titles[i] + " language server",
                prefs.getBoolean(key, false),
                (button, checked) -> {
                    if (!checked) {
                        prefs.edit().putBoolean(key, false).apply();
                        return;
                    }
                    if (com.larv.ide.infrastructure.analysis.LanguageServerManager
                        .warningAccepted(prefs)) {
                        prefs.edit().putBoolean(key, true).apply();
                    } else {
                        button.setChecked(false);
                        confirmLspWarning(activity, prefs, () ->
                            prefs.edit().putBoolean(key, true).apply());
                    }
                }));
            EditText command = new EditText(activity);
            command.setHint(defaults[i]);
            command.setText(com.larv.ide.infrastructure.analysis.LanguageServerManager
                .commandFor(prefs, lang));
            command.setSingleLine(true);
            command.setTextSize(12);
            command.setBackground(ContextCompat.getDrawable(activity, R.drawable.edittext_ide));
            command.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
            command.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));
            pane.addView(command);
            commandFields.add(command);
            commandLangs.add(lang);
        }
        pane.setTag(new Object[]{commandFields, commandLangs});
    }

    private static void confirmLspWarning(Activity activity, SharedPreferences prefs,
                                          Runnable onAccepted) {
        new AlertDialog.Builder(activity)
            .setTitle("High memory usage")
            .setMessage("Language servers run as extra processes inside the Linux runtime. "
                + "Java's jdtls alone can use 200–400 MB of RAM. On a low-end device this "
                + "will reduce performance and may crash apps when memory runs out.\n\n"
                + "Only continue if you understand this and have installed the server package.")
            .setPositiveButton("I understand", (d, w) -> {
                prefs.edit().putBoolean("lsp.warningAccepted", true).apply();
                onAccepted.run();
            })
            .setNegativeButton("Cancel", null)
            .show();
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
        com.larv.ide.infrastructure.run.backend.embedded.PrefixInstaller installer =
            new com.larv.ide.infrastructure.run.backend.embedded.PrefixInstaller(activity);
        boolean ready =
            com.larv.ide.infrastructure.run.backend.embedded.EmbeddedRuntime.isEmbeddedReady(activity);
        boolean arm64 =
            com.larv.ide.infrastructure.run.backend.embedded.EmbeddedRuntime.isArm64();

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
                com.larv.ide.infrastructure.run.backend.embedded.EmbeddedRuntime.isEmbeddedReady(activity);
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
            if (com.larv.ide.infrastructure.run.backend.embedded.EmbeddedRuntime.isEmbeddedReady(activity)) {
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
                new com.larv.ide.infrastructure.run.backend.embedded.PrefixInstaller.Listener() {
                    @Override public void onProgress(String stage, int percent) {
                        status.setText(stage);
                        if (percent > 0) {
                            progress.setIndeterminate(false);
                            progress.setProgress(Math.min(100, percent));
                        }
                    }
                    @Override public void onComplete() {
                        com.larv.ide.infrastructure.project.ProjectManager.setCppEnabled(true);
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
                    com.larv.ide.infrastructure.project.ProjectManager.setCppEnabled(false);
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
            new com.larv.ide.infrastructure.concurrent.IOExecutor(1).execute(task);
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
