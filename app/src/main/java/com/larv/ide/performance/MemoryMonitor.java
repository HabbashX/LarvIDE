package com.larv.ide.performance;

import android.util.Log;

/**
 * Lightweight memory reporting for debug builds. Never forces GC.
 */
public final class MemoryMonitor {

    private static final String TAG = "LarvMemory";

    private MemoryMonitor() {
    }

    public static void logHeap(String label) {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        Log.d(TAG, label + ": used=" + (used / 1024) + "KB"
            + " free=" + (rt.freeMemory() / 1024) + "KB"
            + " max=" + (max / 1024) + "KB");
    }
}
