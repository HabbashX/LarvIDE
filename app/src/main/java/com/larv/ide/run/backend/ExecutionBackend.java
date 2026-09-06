package com.larv.ide.run.backend;

public interface ExecutionBackend {

    enum SetupState {
        READY,
        // Legacy external-Termux states (kept dead — see TermuxCommandBackend).
        TERMUX_MISSING,
        TERMUX_PLAY_BUILD,
        PERMISSION_NOT_GRANTED,
        EXTERNAL_APPS_UNKNOWN,
        // Embedded in-app Linux runtime (Termux bootstrap + proot, no external app).
        EMBEDDED_MISSING,
        EMBEDDED_INSTALLING,
        EMBEDDED_ERROR
    }

    boolean isAvailable();

    SetupState setupState();

    void execute(ExecRequest request) throws BackendUnavailableException;
}
