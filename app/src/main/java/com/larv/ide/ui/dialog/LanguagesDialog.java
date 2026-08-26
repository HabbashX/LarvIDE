package com.larv.ide.ui.dialog;

import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.larv.ide.R;
import com.larv.ide.run.backend.ExecRequest;
import com.larv.ide.run.backend.termux.TermuxCommandBackend;
import com.larv.ide.run.backend.termux.TermuxEnvironment;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public class LanguagesDialog {

    private record Row(String title, String builtinStatus, String termuxPkg,
                       String termuxStatus) { }

    public static void show(Activity activity, TermuxCommandBackend backend,
                            File projectWorkdir, Consumer<String> installer) {
        TermuxEnvironment env = TermuxEnvironment.create();
        boolean termuxOk = env.isUsable(activity);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 24);
        root.setPadding(pad, dp(activity, 8), pad, dp(activity, 8));

        TextView note = new TextView(activity);
        note.setText(termuxOk
            ? "Termux runtime connected — heavy toolchains run through it."
            : "Termux is not connected yet. Java/C/C++/Rust need it; "
                + "Java/JS/HTML/CSS already work without it.");
        note.setTextColor(ContextCompat.getColor(activity,
            termuxOk ? R.color.success : R.color.warning));
        note.setTextSize(12);
        root.addView(note);

        Map<String, String> installs = new LinkedHashMap<>();
        installs.put("Python 3.14", null);
        installs.put("JavaScript (Rhino)", null);
        installs.put("HTML / CSS", null);
        installs.put("Java (OpenJDK 17)", "openjdk-17");
        installs.put("C / C++ (Clang)", "clang");
        installs.put("Node.js", "nodejs");
        installs.put("Rust", "rust");
        installs.put("Ruby", "ruby");

        for (Map.Entry<String, String> e : installs.entrySet()) {
            String pkg = e.getValue();
            String status;
            View action = null;
            if (pkg == null) {
                status = "Built-in — ready";
            } else if (!termuxOk) {
                status = "Requires Termux setup";
            } else {
                status = "Installed via Termux · tap to (re)install";
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
            .setTitle("Languages & Runtimes")
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
