package com.larv.ide.analysis;

import androidx.annotation.NonNull;

import com.larv.ide.completion.CompletionItem;
import com.larv.ide.completion.ProjectIndexer;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Schedules autocomplete and drops results that no longer correspond to the
 * latest keystroke. Prevents the classic mobile failure mode where a slow
 * completion queue keeps posting stale popups while the user types.
 */
public final class CompletionManager {

    public interface Callback { void onCompletions(List<CompletionItem> completions); }
    private final ProjectIndexer indexer;
    private final Executor executor;
    private final AnalysisCancellation cancellation = new AnalysisCancellation();

    public CompletionManager(ProjectIndexer indexer, Executor executor) {
        this.indexer = indexer;
        this.executor = executor;
    }

    public void request(@NonNull String file, int line, int column, String prefix,
                        String memberOf, String content, @NonNull Callback callback) {
        final int generation = cancellation.nextGeneration();
        try {
            executor.execute(() -> {
                if (cancellation.isStale(generation)) return;
                List<CompletionItem> completions = indexer.getCompletions(
                    prefix == null ? "" : prefix, file, line, column, memberOf, content);
                // Results that arrived after a newer request are dropped.
                if (cancellation.isStale(generation)) return;
                callback.onCompletions(completions);
            });
        } catch (RejectedExecutionException e) {
            // Queue full: answer with an empty list so the editor never hangs.
            callback.onCompletions(Collections.emptyList());
        }
    }

    /** Invalidate pending requests (tab switch, editor close). */
    public void cancelPending() {
        cancellation.cancel();
    }
}
