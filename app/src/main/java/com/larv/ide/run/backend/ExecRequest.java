package com.larv.ide.run.backend;

import java.util.Collections;
import java.util.List;

public class ExecRequest {

    private final List<String> command;
    private final String workdir;
    private final boolean interactive;

    public ExecRequest(List<String> command, String workdir, boolean interactive) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        this.command = Collections.unmodifiableList(command);
        this.workdir = workdir;
        this.interactive = interactive;
    }

    public List<String> getCommand() {
        return command;
    }

    public String getWorkdir() {
        return workdir;
    }

    public boolean isInteractive() {
        return interactive;
    }
}
