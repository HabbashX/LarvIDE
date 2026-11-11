package com.larv.ide;

import android.content.Context;
import androidx.multidex.MultiDexApplication;

public class LarvApplication extends MultiDexApplication {

    private static LarvApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        if (com.larv.ide.BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build());
            android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build());
            com.larv.ide.performance.MemoryMonitor.logHeap("startup");
        }
    }

    public static Context getContext() {
        return instance;
    }

    public static LarvApplication getInstance() {
        return instance;
    }
}
