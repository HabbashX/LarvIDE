package com.larv.ide.concurrent;

/**
 * Centralized background task management. Owns the I/O, CPU and compilation
 * executors and shuts them down together.
 */
public final class TaskScheduler {

    private final IOExecutor ioExecutor;
    private final CPUExecutor cpuExecutor;
    private final CompilationExecutor compilationExecutor;

    public TaskScheduler() {
        this(new IOExecutor(1, 2), new CPUExecutor(), new CompilationExecutor());
    }

    public TaskScheduler(IOExecutor io, CPUExecutor cpu, CompilationExecutor comp) {
        this.ioExecutor = io;
        this.cpuExecutor = cpu;
        this.compilationExecutor = comp;
    }

    public IOExecutor io() {
        return ioExecutor;
    }

    public CPUExecutor cpu() {
        return cpuExecutor;
    }

    public CompilationExecutor compilation() {
        return compilationExecutor;
    }

    public void shutdown() {
        ioExecutor.shutdown();
        cpuExecutor.shutdown();
        compilationExecutor.shutdown();
    }
}
