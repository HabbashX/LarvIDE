package com.larv.ide.infrastructure.analysis;

import android.os.Handler;
import android.os.Looper;

import com.larv.ide.infrastructure.model.Diagnostic;
import com.larv.ide.infrastructure.performance.PerformanceConfig;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Debounced, cancellable inspection scheduling. Only the newest request for
 * the newest editor content is ever allowed to publish markers, so a slow
 * analysis on a low-end device can never flood the UI with stale problems.
 */
public final class InspectionManager {

    public interface Callback {
        void onInspections(String file, List<Diagnostic> diagnostics);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor executor;
    private final AnalysisCancellation cancellation = new AnalysisCancellation();
    private Runnable pending;

    public InspectionManager(Executor executor) {
        this.executor = executor;
    }

    /** Debounced — use for content changes. */
    public void request(String file, String content, InspectionConfig config, Callback callback) {
        if (pending != null) handler.removeCallbacks(pending);
        pending = () -> run(file, content, config, callback);
        handler.postDelayed(pending, PerformanceConfig.INSPECTION_DEBOUNCE_MS);
    }

    /** Immediate — use when a file is opened or the tab changes. */
    public void requestNow(String file, String content, InspectionConfig config, Callback callback) {
        if (pending != null) {
            handler.removeCallbacks(pending);
            pending = null;
        }
        run(file, content, config, callback);
    }

    private void run(String file, String content, InspectionConfig config, Callback callback) {
        if (config == null || !config.live) {
            callback.onInspections(file, Collections.emptyList());
            return;
        }
        final int generation = cancellation.nextGeneration();
        try {
            executor.execute(() -> {
                try {
                    if (cancellation.isStale(generation)) return;
                    List<Diagnostic> result = InspectionEngine.inspect(file, content, config);
                    if (cancellation.isStale(generation)) return;
                    callback.onInspections(file, result);
                } catch (Exception e) {
                    if (!cancellation.isStale(generation)) {
                        callback.onInspections(file, Collections.emptyList());
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            callback.onInspections(file, Collections.emptyList());
        }
    }

    public void cancelPending() {
        if (pending != null) {
            handler.removeCallbacks(pending);
            pending = null;
        }
        cancellation.cancel();
    }
}
