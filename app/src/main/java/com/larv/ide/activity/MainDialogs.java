package com.larv.ide.activity;

import com.larv.ide.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.larv.ide.ui.dialog.SettingsDialog;

/**
 * Owns the IDE's dialogs: Help, About, Settings, Linux-runtime setup and the
 * shared input/scroll helpers they use. Extracted from MainActivity so the
 * activity only coordinates state.
 */
final class MainDialogs {

    private MainDialogs() {
    }

    static void showHelp(Activity activity) {
        android.widget.ScrollView scroller = new android.widget.ScrollView(activity);
        android.widget.LinearLayout root = new android.widget.LinearLayout(activity);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        root.setPadding(pad, dp(activity, 12), pad, dp(activity, 8));
        scroller.addView(root);

        android.widget.TextView t = new android.widget.TextView(activity);
        t.setText("LarvIDE Help\n\n"
            + "1. Create a project from Build - (project creator) or the welcome screen.\n"
            + "2. Open a file and edit it in Monaco.\n"
            + "3. Run only works for runnable files with a real entry point:\n"
            + "   Java needs static void main(String[]), C/C++ needs int main().\n"
            + "4. Python/JavaScript scripts run directly; HTML/CSS open the preview.\n"
            + "5. Install toolchains from Languages & Runtimes (nothing is bundled).\n"
            + "6. Open a real Linux terminal from Build - Open Terminal.\n"
            + "7. Use Search - Search in Project to find text across files.\n"
            + "8. Sessions (tabs, cursors, unsaved buffers) are restored automatically.\n");
        t.setTextSize(13);
        t.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        root.addView(t);

        new AlertDialog.Builder(activity)
            .setTitle("Help")
            .setView(scroller)
            .setPositiveButton("Close", null)
            .show();
    }

    static void showSettings(Activity activity, SharedPreferences prefs, int accentColor,
                             Runnable applyEditorPrefs, Runnable onAccentChanged) {
        SettingsDialog.show(activity, new SettingsDialog.Callbacks() {
            @Override public SharedPreferences prefs() { return prefs; }
            @Override public int accentColor() { return accentColor; }
            @Override public void onAccentChanged(String newAccent) { onAccentChanged.run(); }
            @Override public void onEditorSettingsApplied(String themeId, String fontFamily,
                                                          int fontSize, int tabSize) {
                // Prefs are already saved by the dialog; apply live now + once
                // more after a beat (covers a WebView that is still loading).
                // onEditorReady re-applies on next open regardless.
                applyEditorPrefs.run();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    applyEditorPrefs, 600);
                Toast.makeText(activity,
                    "Editor: " + fontSize + " pt applied", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** In-app Linux runtime setup: download bootstrap (arm64, WiFi) - no external app. */
    static void showEmbeddedSetup(Activity activity,
                                  com.larv.ide.run.backend.embedded.EmbeddedLinuxBackend backend,
                                  SharedPreferences prefs, TextView statusText) {
        com.larv.ide.run.backend.ExecutionBackend.SetupState state =
            backend != null ? backend.setupState()
                : com.larv.ide.run.backend.ExecutionBackend.SetupState.EMBEDDED_MISSING;
        if (state == com.larv.ide.run.backend.ExecutionBackend.SetupState.READY) {
            Toast.makeText(activity, "Linux runtime ready", Toast.LENGTH_SHORT).show();
            return;
        }
        if (state == com.larv.ide.run.backend.ExecutionBackend.SetupState.EMBEDDED_INSTALLING) {
            Toast.makeText(activity, "Linux runtime is downloading-", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(activity)
            .setTitle("Linux runtime required")
            .setMessage("C/C++ and real-world toolchains (clang, javac, python, node) run "
                + "inside LarvIDE - no external app needed.\n\n"
                + "Download the Linux bootstrap (~100-150 MB, arm64, WiFi recommended) "
                + "to unlock C/C++ templates and Run?")
            .setPositiveButton("Download", (d, w) -> {
                Toast.makeText(activity, "Downloading Linux runtime-", Toast.LENGTH_LONG).show();
                statusText.setText("Downloading Linux runtime-");
                backend.installer().installAsync(
                    prefs.getString("embeddedBootstrapUrl", null),
                    new com.larv.ide.run.backend.embedded.PrefixInstaller.Listener() {
                        @Override public void onProgress(String stage, int percent) {
                            activity.runOnUiThread(() -> statusText.setText(stage));
                        }
                        @Override public void onComplete() {
                            activity.runOnUiThread(() -> {
                                com.larv.ide.project.ProjectManager.setCppEnabled(true);
                                statusText.setText("Linux runtime ready");
                                Toast.makeText(activity,
                                    "Linux runtime ready - C/C++ unlocked",
                                    Toast.LENGTH_LONG).show();
                            });
                        }
                        @Override public void onError(String message) {
                            activity.runOnUiThread(() -> {
                                statusText.setText("Linux runtime failed");
                                Toast.makeText(activity,
                                    "Download failed: " + message, Toast.LENGTH_LONG).show();
                            });
                        }
                    });
            })
            .setNegativeButton("Later", null)
            .show();
    }

    static void showAbout(Activity activity, int accentColor) {
        String version = "1.1";
        try {
            version = activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }

        android.widget.ScrollView scroller = new android.widget.ScrollView(activity);
        android.widget.LinearLayout root = new android.widget.LinearLayout(activity);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        root.setPadding(pad, dp(activity, 12), pad, dp(activity, 8));
        scroller.addView(root);

        android.widget.TextView title = new android.widget.TextView(activity);
        title.setText("LarvIDE");
        title.setTextSize(22);
        title.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        android.widget.TextView subtitle = new android.widget.TextView(activity);
        subtitle.setText("Real-World Java IDE for Android  -  v" + version);
        subtitle.setTextSize(12);
        subtitle.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        subtitle.setPadding(0, dp(activity, 2), 0, dp(activity, 12));
        root.addView(subtitle);

        root.addView(createAboutDivider(activity));
        root.addView(createAboutSectionTitle(activity, "Purpose"));
        root.addView(createAboutBody(activity,
            "LarvIDE was built for university students in Gaza who cannot afford laptops. "
            + "Many students need to pass Java, data-structures and algorithms assignments on a phone alone. "
            + "LarvIDE gives them a complete, offline-first IDE - editor, compiler, runner and terminal - right on Android."));

        root.addView(createAboutSectionTitle(activity, "Developer"));
        root.addView(createAboutBody(activity, "Project Developed By Abd Allah Al Habbash"));
        root.addView(createAboutLinkRow(activity, accentColor,
            "GitHub: github.com/HabbashX", "https://github.com/HabbashX"));
        root.addView(createAboutLinkRow(activity, accentColor,
            "Email: abdalahalhabbash@gmail.com", "mailto:abdalahalhabbash@gmail.com"));
        root.addView(createAboutLinkRow(activity, accentColor,
            "Phone: 0567162449", "tel:0567162449"));

        root.addView(createAboutDivider(activity));
        root.addView(createAboutSectionTitle(activity, "Support the Project  -  Donate"));
        root.addView(createAboutBody(activity,
            "You can help me to develop the IDE and enhance it for more students. "
            + "Every donation - even small - keeps the project alive. Thank you!"));

        android.widget.LinearLayout donateCard = new android.widget.LinearLayout(activity);
        donateCard.setOrientation(android.widget.LinearLayout.VERTICAL);
        donateCard.setBackground(ContextCompat.getDrawable(activity, R.drawable.edittext_ide));
        donateCard.setPadding(dp(activity, 14), dp(activity, 12),
            dp(activity, 14), dp(activity, 12));
        android.widget.LinearLayout.LayoutParams cardLp =
            new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(activity, 8);
        donateCard.setLayoutParams(cardLp);

        android.widget.TextView donateLabel = new android.widget.TextView(activity);
        donateLabel.setText("PalPay Wallet");
        donateLabel.setTextSize(11);
        donateLabel.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        donateCard.addView(donateLabel);

        android.widget.TextView walletNumber = new android.widget.TextView(activity);
        walletNumber.setText("0567162449");
        walletNumber.setTextSize(18);
        walletNumber.setTextColor(accentColor);
        walletNumber.setTypeface(null, android.graphics.Typeface.BOLD);
        walletNumber.setTextIsSelectable(true);
        donateCard.addView(walletNumber);

        android.widget.TextView walletOwner = new android.widget.TextView(activity);
        walletOwner.setText("Wallet owner: Abd Allah Al Habbash");
        walletOwner.setTextSize(12);
        walletOwner.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        walletOwner.setPadding(0, dp(activity, 2), 0, 0);
        donateCard.addView(walletOwner);

        root.addView(donateCard);

        android.widget.TextView licenceNote = new android.widget.TextView(activity);
        licenceNote.setText(
            "Licence: LarvIDE Educational Source-Available v1.0  -  Free for study & private modification. "
            + "Publishing a modified version requires permission. See PERMISSIONS.md");
        licenceNote.setTextSize(10);
        licenceNote.setTextColor(ContextCompat.getColor(activity, R.color.text_disabled));
        licenceNote.setPadding(0, dp(activity, 16), 0, 0);
        root.addView(licenceNote);

        new AlertDialog.Builder(activity)
            .setView(scroller)
            .setPositiveButton("Close", null)
            .setNeutralButton("GitHub", (d, w) -> {
                try {
                    activity.startActivity(new android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/HabbashX")));
                } catch (Exception ignored) {
                }
            })
            .setNegativeButton("Copy Wallet", (d, w) -> {
                android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) activity.getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "PalPay Wallet", "0567162449"));
                    Toast.makeText(activity, "Wallet number copied: 0567162449",
                        Toast.LENGTH_SHORT).show();
                }
            })
            .show();
    }

    @androidx.annotation.NonNull
    static EditText createIdeInput(Activity activity, String hint) {
        EditText input = new EditText(activity);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        if (hint != null) {
            input.setHint(hint);
        }
        input.setTextSize(14);
        input.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(activity, R.color.text_disabled));
        input.setBackground(ContextCompat.getDrawable(activity, R.drawable.edittext_ide));
        int p = dp(activity, 12);
        input.setPadding(p, dp(activity, 10), p, dp(activity, 10));
        return input;
    }

    @androidx.annotation.NonNull
    static View wrapDialogView(Activity activity, View view) {
        android.widget.LinearLayout box = new android.widget.LinearLayout(activity);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(activity, 24), dp(activity, 4), dp(activity, 24), dp(activity, 8));
        box.addView(view, new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    static void deleteRecursive(java.io.File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            java.io.File[] children = file.listFiles();
            if (children != null) {
                for (java.io.File child : children) deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static android.widget.TextView createAboutSectionTitle(Activity activity, String text) {
        android.widget.TextView tv = new android.widget.TextView(activity);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        tv.setPadding(0, dp(activity, 14), 0, dp(activity, 4));
        return tv;
    }

    private static android.widget.TextView createAboutBody(Activity activity, String text) {
        android.widget.TextView tv = new android.widget.TextView(activity);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        tv.setLineSpacing(dp(activity, 2), 1.0f);
        return tv;
    }

    private static View createAboutDivider(Activity activity) {
        View v = new View(activity);
        v.setBackgroundColor(ContextCompat.getColor(activity, R.color.divider));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1));
        lp.topMargin = dp(activity, 10);
        lp.bottomMargin = dp(activity, 4);
        v.setLayoutParams(lp);
        return v;
    }

    private static android.widget.LinearLayout createAboutLinkRow(Activity activity,
                                                                  int accentColor,
                                                                  String label, String url) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(activity);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(activity, 4), 0, dp(activity, 2));

        android.widget.TextView tv = new android.widget.TextView(activity);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(accentColor);
        tv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tv.setOnClickListener(v -> {
            try {
                activity.startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
            } catch (Exception ignored) {
            }
        });
        row.addView(tv);

        android.widget.TextView action = new android.widget.TextView(activity);
        action.setText("->");
        action.setTextSize(14);
        action.setTextColor(accentColor);
        action.setPadding(dp(activity, 8), 0, 0, 0);
        row.addView(action);
        row.setOnClickListener(v -> tv.performClick());
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        return row;
    }
}
