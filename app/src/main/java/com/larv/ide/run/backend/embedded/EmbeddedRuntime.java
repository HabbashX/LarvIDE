package com.larv.ide.run.backend.embedded;

import android.content.Context;

import java.io.File;

/**
 * Static helpers for the embedded in-app Linux runtime.
 * Single source of truth for "is the real toolchain available without
 * opening any external app?".
 */
public final class EmbeddedRuntime {

    private EmbeddedRuntime() {}

    public static File prefixDir(Context context) {
        return new File(context.getFilesDir(), "usr");
    }

    public static File bashFile(Context context) {
        return new File(prefixDir(context), "bin/bash");
    }

    public static boolean isEmbeddedReady(Context context) {
        if (context == null) return false;
        File bash = bashFile(context);
        return bash.exists() && bash.canExecute();
    }

    /** C/C++ creation + Run is hidden until the embedded runtime is READY. */
    public static boolean isCppSupported(Context context) {
        return isEmbeddedReady(context);
    }

    public static boolean isArm64() {
        String abi = android.os.Build.SUPPORTED_ABIS.length > 0
            ? android.os.Build.SUPPORTED_ABIS[0] : "";
        return abi.contains("arm64");
    }
}
