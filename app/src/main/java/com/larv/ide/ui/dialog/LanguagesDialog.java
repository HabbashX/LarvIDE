package com.larv.ide.ui.dialog;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.larv.ide.R;
import com.larv.ide.run.backend.embedded.EmbeddedRuntime;
import com.larv.ide.setup.SetupConfig;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Toolchains are not built into the app. Every entry here installs into the
 * embedded Linux prefix; there are no "built-in" runtimes to fall back on.
 */
public class LanguagesDialog {

    public static void show(Activity activity, File projectWorkdir,
                            Consumer<String> installer) {
        boolean embeddedReady = EmbeddedRuntime.isEmbeddedReady(activity);
        SharedPreferences prefs = activity.getSharedPreferences("larv_ide",
            Activity.MODE_PRIVATE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 24);
        root.setPadding(pad, dp(activity, 8), pad, dp(activity, 8));

        TextView note = new TextView(activity);
        note.setText(embeddedReady
            ? "All languages run inside the embedded Linux environment. "
                + "Install a toolchain below — nothing is bundled in the APK."
            : "Linux environment not downloaded yet. Compilers and runtimes "
                + "unlock after the one-time setup download.");
        note.setTextColor(ContextCompat.getColor(activity,
            embeddedReady ? R.color.success : R.color.warning));
        note.setTextSize(12);
        root.addView(note);

        Map<String, String> packages = new LinkedHashMap<>();
        packages.put("☕ Java (OpenJDK 17 — javac + JRE)", "openjdk-17");
        packages.put("🐍 Python 3", "python");
        packages.put("🟢 JavaScript / Node.js", "nodejs");
        packages.put("⚙️ C / C++ (Clang)", "clang");
        packages.put("🐹 Go", "golang");
        packages.put("🐘 PHP", "php");
        packages.put("💎 Ruby", "ruby");
        packages.put("🦀 Rust", "rust");
        packages.put("🐪 Perl", "perl");
        packages.put("🌙 Lua", "lua");

        for (Map.Entry<String, String> e : packages.entrySet()) {
            String pkg = e.getValue();
            boolean installed = prefs.getBoolean(SetupConfig.toolchainFlag(pkg), false);
            String status;
            View action = null;
            if (!embeddedReady) {
                status = "Locked — download the Linux environment first";
            } else if (installed) {
                status = pkg + " · installed ✓";
            } else {
                status = pkg + " · not installed";
                Button b = new Button(activity, null, 0);
                b.setText("Install " + pkg);
                b.setTextColor(ContextCompat.getColor(activity, R.color.ide_accent));
                b.setTextSize(12);
                b.setOnClickListener(v -> installer.accept(pkg));
                action = b;
            }
            root.addView(row(activity, e.getKey(), status, action));
        }

        ScrollView scroller = new ScrollView(activity);
        scroller.addView(root);

        new AlertDialog.Builder(activity)
            .setTitle("Languages & Toolchains")
            .setView(scroller)
            .setPositiveButton("Close", null)
            .show();
    }

    private static LinearLayout row(Activity activity, String language, String status,
                                    View action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(activity, 10), 0, dp(activity, 2));

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(activity);
        t.setText(language);
        t.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        t.setTextSize(14);
        col.addView(t);

        TextView s = new TextView(activity);
        s.setText(status);
        s.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        s.setTextSize(12);
        col.addView(s);

        row.addView(col);
        if (action != null) row.addView(action);
        return row;
    }

    private static int dp(Activity activity, int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
