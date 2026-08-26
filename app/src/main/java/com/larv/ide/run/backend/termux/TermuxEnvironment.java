package com.larv.ide.run.backend.termux;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import com.larv.ide.run.backend.ExecutionBackend;

public class TermuxEnvironment {

    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";
    static final String PLAY_INSTALLER = "com.android.vending";

    private TermuxEnvironment() {
    }

    public static TermuxEnvironment create() {
        return new TermuxEnvironment();
    }

    public boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public String installedVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                .getPackageInfo(TERMUX_PACKAGE, 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    public boolean isPlayStoreBuild(Context context) {
        try {
            String installer = null;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                installer = context.getPackageManager()
                    .getInstallSourceInfo(TERMUX_PACKAGE)
                    .getInitiatingPackageName();
            } else {
                installer = context.getPackageManager()
                    .getInstallerPackageName(TERMUX_PACKAGE);
            }
            return PLAY_INSTALLER.equals(installer);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean hasRunCommandPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, RUN_COMMAND_PERMISSION)
            == PackageManager.PERMISSION_GRANTED;
    }

    public ExecutionBackend.SetupState setupState(Context context) {
        if (!isInstalled(context)) {
            return ExecutionBackend.SetupState.TERMUX_MISSING;
        }
        if (isPlayStoreBuild(context)) {
            return ExecutionBackend.SetupState.TERMUX_PLAY_BUILD;
        }
        if (!hasRunCommandPermission(context)) {
            return ExecutionBackend.SetupState.PERMISSION_NOT_GRANTED;
        }
        return ExecutionBackend.SetupState.EXTERNAL_APPS_UNKNOWN;
    }

    public boolean isUsable(Context context) {
        ExecutionBackend.SetupState state = setupState(context);
        return state == ExecutionBackend.SetupState.READY
            || state == ExecutionBackend.SetupState.EXTERNAL_APPS_UNKNOWN;
    }
}
