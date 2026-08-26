package com.larv.ide.run.backend;

public interface ExecutionBackend {

    enum SetupState {
        READY,
        TERMUX_MISSING,
        TERMUX_PLAY_BUILD,
        PERMISSION_NOT_GRANTED,
        EXTERNAL_APPS_UNKNOWN
    }

    boolean isAvailable();

    SetupState setupState();

    void execute(ExecRequest request) throws BackendUnavailableException;
}
