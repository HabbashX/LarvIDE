package com.larv.ide.ui.fragment;

import android.annotation.SuppressLint;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.larv.ide.R;
import com.larv.ide.terminal.EmbeddedLinuxSession;
import com.larv.ide.terminal.EmbeddedShellSession;
import com.larv.ide.ui.view.SafeEmulatorView;

import java.io.File;

import jackpal.androidterm.emulatorview.ColorScheme;
import jackpal.androidterm.emulatorview.TermSession;

public class TerminalFragment extends Fragment {

    private static final ColorScheme DARK_SCHEME = new ColorScheme(
        0xFFBCBEC4, 0xFF141517, 0xFF141517, 0xFF56A8F5);

    private static final String[] EXTRA_KEYS = {
        "Ctrl", "Esc", "Tab", "Paste", "↑", "↓", "←", "→", "-", "/", "|", "~"
    };

    private FrameLayout terminalContainer;
    private TextView emptyView;
    private android.widget.HorizontalScrollView extraKeysBar;
    private SafeEmulatorView terminalView;
    private EmbeddedShellSession shellSession;
    private EmbeddedLinuxSession linuxSession;
    private TermSession activeSession;
    private File pendingWorkdir;
    private boolean startPending = false;

    // Item 5: working directory per terminal (remember last cd)
    private String lastWorkdir;

    // Item 4: terminal scrollback capture
    private final StringBuilder scrollback = new StringBuilder();
    private static final int SCROLLBACK_MAX = 20000; // cap at ~20k chars

    // Item 5: line buffer for tracking "cd" commands
    private final StringBuilder lineBuffer = new StringBuilder();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_terminal, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        emptyView = view.findViewById(R.id.terminalEmpty);
        terminalContainer = view.findViewById(R.id.terminalContainer);
        extraKeysBar = view.findViewById(R.id.terminalExtraKeys);
        if (startPending) {
            startPending = false;
            openSession(pendingWorkdir);
        }
    }

    public void openSession(@Nullable File workingDirectory) {
        if (terminalContainer == null) {
            pendingWorkdir = workingDirectory;
            startPending = true;
            return;
        }
        if (!com.larv.ide.run.backend.embedded.EmbeddedRuntime.isEmbeddedReady(requireContext())) {
            closeSession();
            terminalContainer.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText("Linux runtime not installed.\\n\\n"
                + "Download it from Languages & Toolchains (or the setup wizard) "
                + "to open a real Linux terminal.");
            return;
        }
        // Item 5: restore last working directory for this project
        String projectKey = workingDirectory != null
            ? workingDirectory.getAbsolutePath() : "default";
        SharedPreferences prefs = requireContext().getSharedPreferences(
            "larv_terminal", Context.MODE_PRIVATE);
        String savedWorkdir = prefs.getString("workdir_" + projectKey, null);
        File effectiveWorkdir = savedWorkdir != null
            ? new File(savedWorkdir) : workingDirectory;
        // Item 4: restore scrollback
        String savedScrollback = prefs.getString("scrollback_" + projectKey, null);
        if (savedScrollback != null && !savedScrollback.isEmpty()) {
            scrollback.setLength(0);
            scrollback.append(savedScrollback);
        }

        // Reuse a still-running shell instead of killing it on every open.
        if (activeSession != null && isSessionAlive()) {
            emptyView.setVisibility(View.GONE);
            terminalContainer.setVisibility(View.VISIBLE);
            if (terminalView == null || terminalView.getParent() == null) {
                attachView(activeSession);
            }
            if (terminalView != null) terminalView.requestFocus();
            showExtraKeysBar();
            return;
        }
        closeSession();
        emptyView.setVisibility(View.GONE);
        terminalContainer.setVisibility(View.VISIBLE);

        TermSession session = newEmbeddedSession(effectiveWorkdir);

        attachView(session);
        showExtraKeysBar();

        // Item 5: save workdir for next time
        if (effectiveWorkdir != null) {
            prefs.edit().putString("workdir_" + projectKey,
                effectiveWorkdir.getAbsolutePath()).apply();
        }
        // Item 4: save scrollback
        prefs.edit().putString("scrollback_" + projectKey, scrollback.toString()).apply();
    }

    private void attachView(TermSession session) {
        SafeEmulatorView view = new SafeEmulatorView(requireContext(), session,
            getResources().getDisplayMetrics());
        view.setTextSize(13);
        view.setUseCookedIME(false);
        view.setColorScheme(DARK_SCHEME);

        terminalContainer.addView(view, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        terminalView = view;
        view.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) requireContext()
                .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view,
                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private boolean isSessionAlive() {
        if (linuxSession != null) return linuxSession.isAlive();
        return activeSession != null;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private TermSession newEmbeddedSession(@Nullable File workingDirectory) {
        // Prefer embedded Linux bash (no external app); toybox fallback inside.
        linuxSession = new EmbeddedLinuxSession(requireContext(), workingDirectory,
            new EmbeddedLinuxSession.OutputListener() {
                @Override public void onOutput(byte[] data, int len) {
                    // Item 4: capture terminal output for scrollback persistence
                    String chunk = new String(data, 0, len,
                        java.nio.charset.StandardCharsets.UTF_8);
                    scrollback.append(chunk);
                    if (scrollback.length() > SCROLLBACK_MAX) {
                        scrollback.delete(0, scrollback.length() - SCROLLBACK_MAX);
                    }
                }
            });
        activeSession = linuxSession.getSession();
        return activeSession;
    }

    private void showExtraKeysBar() {
        if (extraKeysBar == null || getContext() == null) return;
        android.widget.LinearLayout row =
            (android.widget.LinearLayout) extraKeysBar.getChildAt(0);
        if (row == null) return;
        if (row.getChildCount() > 0) {
            extraKeysBar.setVisibility(View.VISIBLE);
            return;
        }
        for (String key : EXTRA_KEYS) {
            android.widget.Button b = new android.widget.Button(getContext(), null, 0);
            b.setText(key);
            b.setTextSize(12);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            int p = Math.round(8 * getResources().getDisplayMetrics().density);
            b.setPadding(p, p / 2, p, p / 2);
            b.setOnClickListener(v -> sendExtraKey(key));
            row.addView(b);
        }
        extraKeysBar.setVisibility(View.VISIBLE);
    }

    private void sendExtraKey(String key) {
        if (activeSession == null) return;
        try {
            switch (key) {
                case "Ctrl":
                    if (terminalView != null) terminalView.sendControlKey();
                    break;
                case "Esc": activeSession.write(new byte[]{27}, 0, 1); break;
                case "Tab": activeSession.write(new byte[]{'\t'}, 0, 1); break;
                case "Paste": pasteFromClipboard(); break;
                case "↑": activeSession.write(new byte[]{27, '[', 'A'}, 0, 3); break;
                case "↓": activeSession.write(new byte[]{27, '[', 'B'}, 0, 3); break;
                case "→": activeSession.write(new byte[]{27, '[', 'C'}, 0, 3); break;
                case "←": activeSession.write(new byte[]{27, '[', 'D'}, 0, 3); break;
                default: {
                    activeSession.write(key);
                    // Item 5: track cd commands from typed input
                    char c = key.charAt(0);
                    if (c == '\n' || c == '\r') {
                        String line = lineBuffer.toString().trim();
                        if (line.startsWith("cd ")) {
                            saveWorkdir(line.substring(3).trim());
                        }
                        lineBuffer.setLength(0);
                    } else {
                        lineBuffer.append(c);
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Item 5: save the working directory after a `cd` command. */
    private void saveWorkdir(String target) {
        if (target == null || target.isEmpty()) return;
        File resolved = new File(target);
        if (!resolved.isAbsolute()) {
            File base = pendingWorkdir != null ? pendingWorkdir :
                new File(requireContext().getFilesDir(), "home");
            resolved = new File(base, target);
        }
        String projectKey = pendingWorkdir != null
            ? pendingWorkdir.getAbsolutePath() : "default";
        SharedPreferences prefs = requireContext().getSharedPreferences(
            "larv_terminal", Context.MODE_PRIVATE);
        prefs.edit().putString("workdir_" + projectKey,
            resolved.getAbsolutePath()).apply();
        pendingWorkdir = resolved;
    }

    /** Item 1: paste clipboard contents into the terminal. */
    private void pasteFromClipboard() {
        if (activeSession == null) return;
        ClipboardManager cm = (ClipboardManager) requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clip.getItemCount(); i++) {
            android.content.ClipData.Item item = clip.getItemAt(i);
            if (item.getText() != null) sb.append(item.getText());
            else if (item.getUri() != null) sb.append(item.getUri().toString());
        }
        if (sb.length() == 0) return;
        try {
            byte[] data = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            activeSession.write(data, 0, data.length);
        } catch (Exception ignored) {
        }
    }

    private void closeSession() {
        // Item 4: persist scrollback before destroying
        String projectKey = pendingWorkdir != null
            ? pendingWorkdir.getAbsolutePath() : "default";
        SharedPreferences prefs = requireContext().getSharedPreferences(
            "larv_terminal", Context.MODE_PRIVATE);
        prefs.edit().putString("scrollback_" + projectKey, scrollback.toString()).apply();

        if (terminalView != null && terminalView.getParent() != null) {
            ((ViewGroup) terminalView.getParent()).removeView(terminalView);
        }
        terminalView = null;
        activeSession = null;
        if (linuxSession != null) {
            linuxSession.destroy();
            linuxSession = null;
        }
        if (shellSession != null) {
            shellSession.destroy();
            shellSession = null;
        }
        if (extraKeysBar != null) extraKeysBar.setVisibility(View.GONE);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (terminalView != null) {
            terminalView.onResume();
        }
    }

    @Override
    public void onPause() {
        if (terminalView != null) {
            terminalView.onPause();
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        closeSession();
        super.onDestroyView();
    }
}
