package com.larv.ide.core.run.backend;

public class BackendUnavailableException extends Exception {

    private final ExecutionBackend.SetupState state;

    public BackendUnavailableException(ExecutionBackend.SetupState state) {
        super("Termux backend unavailable: " + state);
        this.state = state;
    }

    public ExecutionBackend.SetupState getState() {
        return state;
    }
}
