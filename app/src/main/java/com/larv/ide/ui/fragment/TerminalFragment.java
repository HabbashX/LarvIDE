package com.larv.ide.ui.fragment;

import android.annotation.SuppressLint;
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
        "Ctrl", "Esc", "Tab", "↑", "↓", "←", "→", "-", "/", "|", "~"
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
        closeSession();
        emptyView.setVisibility(View.GONE);
        terminalContainer.setVisibility(View.VISIBLE);

        TermSession session = newEmbeddedSession(workingDirectory);

        SafeEmulatorView view = new SafeEmulatorView(requireContext(), session,
            getResources().getDisplayMetrics());
        view.setTextSize(13);
        view.setColorScheme(DARK_SCHEME);

        terminalContainer.addView(view, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        terminalView = view;
        view.requestFocus();
        showExtraKeysBar();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private TermSession newEmbeddedSession(@Nullable File workingDirectory) {
        // Prefer embedded Linux bash (no external app); toybox fallback inside.
        linuxSession = new EmbeddedLinuxSession(requireContext(), workingDirectory);
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
                case "↑": activeSession.write(new byte[]{27, '[', 'A'}, 0, 3); break;
                case "↓": activeSession.write(new byte[]{27, '[', 'B'}, 0, 3); break;
                case "→": activeSession.write(new byte[]{27, '[', 'C'}, 0, 3); break;
                case "←": activeSession.write(new byte[]{27, '[', 'D'}, 0, 3); break;
                default: activeSession.write(key); break;
            }
        } catch (Exception ignored) {
        }
    }

    private void closeSession() {
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
