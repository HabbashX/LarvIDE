package com.larv.ide.run.backend.termux;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.larv.ide.R;
import com.larv.ide.run.backend.ExecutionBackend;

/**
 * Legacy external-Termux setup wizard. Kept dead — LarvIDE now uses the embedded
 * in-app Linux runtime (no external app). Removal planned later.
 * @deprecated Use MainActivity.showEmbeddedSetupDialog path instead.
 */
@Deprecated
public class TermuxSetupWizard {

    public static final int PERMISSION_REQUEST_CODE = 4201;

    private static final String F_DROID_URL = "https://f-droid.org/en/packages/com.termux/";
    private static final String PROPERTIES_SNIPPET = "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties";

    private final Activity activity;
    private final TermuxEnvironment environment;
    private LinearLayout stepsBox;
    private androidx.appcompat.app.AlertDialog dialog;

    public TermuxSetupWizard(Activity activity) {
        this.activity = activity;
        this.environment = TermuxEnvironment.create();
    }

    public void show() {
        ScrollView scroller = new ScrollView(activity);
        stepsBox = new LinearLayout(activity);
        stepsBox.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        stepsBox.setPadding(pad, dp(8), pad, dp(8));
        scroller.addView(stepsBox);

        refresh();

        dialog = new androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("Termux Setup")
            .setView(scroller)
            .setPositiveButton("Refresh", (d, w) -> refresh())
            .setNegativeButton("Close", null)
            .show();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> refresh());
    }

    public void onPermissionResult() {
        if (dialog != null && dialog.isShowing()) {
            refresh();
        }
    }

    private void refresh() {
        stepsBox.removeAllViews();
        boolean installed = environment.isInstalled(activity);
        boolean playBuild = installed && environment.isPlayStoreBuild(activity);
        boolean permission = environment.hasRunCommandPermission(activity);

        addStep(installed, "1. Termux app installed",
            installed
                ? "Found Termux " + safeVersion()
                    + " — installer: " + installerSource() + " · pkg: com.termux"
                : "Termux is not installed (package com.termux not visible to LarvIDE).\n"
                    + "If it IS installed, your device may be hiding it — reinstall "
                    + "LarvIDE after Termux, or use a Termux build from F-Droid/GitHub.",
            installed ? null : "Install from F-Droid", v -> openUrl(F_DROID_URL));

        if (installed) {
            addStep(!playBuild, "2. Correct Termux build",
                playBuild
                    ? "This is the deprecated Play Store build — it cannot run commands"
                    : "F-Droid/GitHub build detected",
                playBuild ? "Get F-Droid version" : null, v -> openUrl(F_DROID_URL));

            addStep(permission, "3. Execution permission",
                permission ? "RUN_COMMAND granted to LarvIDE"
                    : "LarvIDE needs the RUN_COMMAND permission",
                permission ? null : "Grant permission", v -> requestPermission());
        }

        String externalHint = installed
            ? "Open Termux → Settings → enable 'Allow external apps', then run this once "
                + "inside Termux:\n" + PROPERTIES_SNIPPET
            : "Complete after installing Termux.";
        addWarning("4. Allow external apps", externalHint);

        TextView footer = new TextView(activity);
        footer.setText(environment.isUsable(activity)
            ? "\nAll required steps are done. If commands still fail, verify step 4."
            : "\nComplete the missing steps above.");
        footer.setTextColor(ContextCompat.getColor(activity,
            environment.isUsable(activity) ? R.color.success : R.color.warning));
        footer.setTextSize(13);
        stepsBox.addView(footer);
    }

    private void addStep(boolean done, String title, String detail, String actionLabel,
                         View.OnClickListener action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(2));

        TextView status = new TextView(activity);
        status.setText(done ? "✅" : "❌");
        status.setTextSize(16);
        status.setPadding(0, 0, dp(10), 0);
        row.addView(status);

        LinearLayout textColumn = new LinearLayout(activity);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        titleView.setTextSize(14);
        textColumn.addView(titleView);

        TextView detailView = new TextView(activity);
        detailView.setText(detail);
        detailView.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        detailView.setTextSize(12);
        textColumn.addView(detailView);

        row.addView(textColumn);

        if (actionLabel != null) {
            Button button = new Button(activity, null, 0);
            button.setText(actionLabel);
            button.setTextSize(12);
            button.setTextColor(ContextCompat.getColor(activity, R.color.ide_accent));
            button.setOnClickListener(action);
            button.setPadding(dp(8), dp(4), dp(8), dp(4));
            row.addView(button);
        }

        stepsBox.addView(row);
    }

    private void addWarning(String title, String message) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(14), 0, 0);

        TextView titleView = new TextView(activity);
        titleView.setText("⚠️ " + title);
        titleView.setTextColor(Color.rgb(242, 197, 92));
        titleView.setTextSize(14);
        box.addView(titleView);

        TextView messageView = new TextView(activity);
        messageView.setText(message);
        messageView.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        messageView.setTextSize(12);
        messageView.setPadding(0, dp(4), 0, 0);
        messageView.setTextIsSelectable(true);
        box.addView(messageView);

        stepsBox.addView(box);
    }

    public void requestPermission() {
        androidx.core.app.ActivityCompat.requestPermissions(activity,
            new String[]{TermuxEnvironment.RUN_COMMAND_PERMISSION},
            PERMISSION_REQUEST_CODE);
    }

    private void openUrl(String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private String safeVersion() {
        String v = environment.installedVersion(activity);
        return v == null ? "" : "(v" + v + ")";
    }

    private String installerSource() {
        if (environment.isPlayStoreBuild(activity)) {
            return "Google Play (BROKEN build)";
        }
        try {
            String src = activity.getPackageManager()
                .getInstallSourceInfo(TermuxEnvironment.TERMUX_PACKAGE)
                .getInitiatingPackageName();
            return src == null ? "unknown/sideloaded" : src;
        } catch (Exception e) {
            return "unknown/sideloaded";
        }
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
