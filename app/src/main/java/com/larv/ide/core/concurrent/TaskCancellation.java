package com.larv.ide.core.concurrent;

/**
 * Cancellation token for background tasks. Implementations must be thread-safe.
 */
public interface TaskCancellation {
    void cancel();

    boolean isCancelled();
}
