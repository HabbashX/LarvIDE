package com.larv.ide.core.run.backend.embedded;

import android.content.Context;

import java.io.File;

/**
 * Static helpers for the embedded in-app Linux runtime.
 * Single source of truth for "is the real toolchain available without
 * opening any external app?".
 */
public final class EmbeddedRuntime {

    private EmbeddedRuntime() {}

    private static volatile Boolean readyCache = null;

    public static File prefixDir(Context context) {
        return new File(context.getFilesDir(), "usr");
    }

    /** Termux-style $HOME; created on demand so proot has a valid /home. */
    public static File homeDir(Context context) {
        File home = new File(context.getFilesDir(), "home");
        if (!home.isDirectory()) home.mkdirs();
        return home;
    }

    public static File bashFile(Context context) {
        return new File(prefixDir(context), "bin/bash");
    }

    /** proot binary, extracted from jniLibs at install time (0755). */
    public static File prootFile(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libproot.so");
    }

    /** proot's W^X-safe loader, extracted from jniLibs at install time. */
    public static File prootLoaderFile(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libproot-loader.so");
    }

    /**
     * Libraries proot needs to start: bash's libandroid-support plus proot's own
     * libtalloc / libandroid-shmem. Without them proot dies before it can map
     * any guest binary, which surfaces as a generic "Permission denied".
     */
    public static boolean hasRuntimeLibraries(Context context) {
        File lib = new File(prefixDir(context), "lib");
        return isNonEmpty(new File(lib, "libandroid-support.so"))
            && isNonEmpty(new File(lib, "libtalloc.so.2"))
            && isNonEmpty(new File(lib, "libandroid-shmem.so"));
    }

    public static boolean isEmbeddedReady(Context context) {
        if (context == null) return false;
        if (readyCache != null) return readyCache;
        File bash = bashFile(context);
        boolean ready = bash.isFile() && bash.canExecute()
            && hasRuntimeLibraries(context)
            && prootFile(context).isFile();
        readyCache = ready;
        return ready;
    }

    public static void invalidateReadyCache() {
        readyCache = null;
    }

    private static boolean isNonEmpty(File f) {
        return f.isFile() && f.length() > 0;
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
