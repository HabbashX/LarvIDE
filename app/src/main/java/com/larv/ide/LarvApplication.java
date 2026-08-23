package com.larv.ide;

import android.content.Context;
import androidx.multidex.MultiDexApplication;

public class LarvApplication extends MultiDexApplication {

    private static LarvApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    public static Context getContext() {
        return instance;
    }

    public static LarvApplication getInstance() {
        return instance;
    }
}
