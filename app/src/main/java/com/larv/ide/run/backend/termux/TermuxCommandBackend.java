package com.larv.ide.run.backend.termux;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.larv.ide.run.backend.BackendUnavailableException;
import com.larv.ide.run.backend.ExecutionBackend;
import com.larv.ide.run.backend.ExecRequest;

import java.util.ArrayList;
import java.util.List;

public class TermuxCommandBackend implements ExecutionBackend {

    public static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    public static final String EXTRA_PATH = "com.termux.RUN_COMMAND_PATH";
    public static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    public static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    public static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";

    public static final String TERMUX_PREFIX = "/data/data/com.termux/files";
    public static final String TERMUX_BIN = TERMUX_PREFIX + "/usr/bin";

    private final Context context;
    private final TermuxEnvironment environment;

    public TermuxCommandBackend(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.environment = TermuxEnvironment.create();
    }

    @Override
    public boolean isAvailable() {
        return environment.isUsable(context);
    }

    @Override
    public ExecutionBackend.SetupState setupState() {
        return environment.setupState(context);
    }

    @Override
    public void execute(ExecRequest request) throws BackendUnavailableException {
        ExecutionBackend.SetupState state = setupState();
        if (state != ExecutionBackend.SetupState.READY
            && state != ExecutionBackend.SetupState.EXTERNAL_APPS_UNKNOWN) {
            throw new BackendUnavailableException(state);
        }
        IntentSpec spec = buildIntentSpec(request);
        Intent intent = new Intent();
        intent.setClassName(TermuxEnvironment.TERMUX_PACKAGE, "com.termux.app.RunCommandService");
        intent.setAction(ACTION_RUN_COMMAND);
        intent.putExtra(EXTRA_PATH, spec.path);
        intent.putExtra(EXTRA_ARGUMENTS, spec.arguments.toArray(new String[0]));
        if (spec.workdir != null) {
            intent.putExtra(EXTRA_WORKDIR, spec.workdir);
        }
        intent.putExtra(EXTRA_BACKGROUND, spec.background);
        context.startService(intent);
    }

    public static class IntentSpec {
        public final String path;
        public final List<String> arguments;
        public final String workdir;
        public final boolean background;

        IntentSpec(String path, List<String> arguments, String workdir, boolean background) {
            this.path = path;
            this.arguments = arguments;
            this.workdir = workdir;
            this.background = background;
        }
    }

    static IntentSpec buildIntentSpec(ExecRequest request) {
        List<String> command = request.getCommand();
        String binary = command.get(0);

        String path;
        List<String> args = new ArrayList<>();
        if (binary.contains("/")) {
            path = binary;
            args.addAll(command.subList(1, command.size()));
        } else if (binary.equals("bash") || binary.equals("sh")) {
            path = TERMUX_BIN + "/" + binary;
            args.addAll(command.subList(1, command.size()));
        } else {
            path = TERMUX_BIN + "/bash";
            args.add("-c");
            args.add(joinCommand(command));
        }
        return new IntentSpec(path, args, request.getWorkdir(), !request.isInteractive());
    }

    private static String joinCommand(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(command.get(i));
        }
        return sb.toString();
    }
}
