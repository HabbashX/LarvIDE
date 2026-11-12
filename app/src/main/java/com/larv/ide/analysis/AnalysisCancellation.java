package com.larv.ide.analysis;

import com.larv.ide.concurrent.TaskCancellation;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generation-based cancellation token. Each {@link #nextGeneration()} call
 * invalidates every task started before it, which is exactly what autocomplete
 * and cancellation of superseded analysis needs.
 */
public final class AnalysisCancellation implements TaskCancellation {

    private final AtomicInteger generation = new AtomicInteger();

    public int nextGeneration() {
        return generation.incrementAndGet();
    }

    public int generation() {
        return generation.get();
    }

    public boolean isStale(int generationTakenWhenStarted) {
        return generation.get() != generationTakenWhenStarted;
    }

    @Override
    public void cancel() {
        generation.incrementAndGet();
    }

    @Override
    public boolean isCancelled() {
        return false;
    }
}
