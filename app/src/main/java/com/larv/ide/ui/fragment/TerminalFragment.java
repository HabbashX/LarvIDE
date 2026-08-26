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
import com.larv.ide.terminal.EmbeddedShellSession;
import com.larv.ide.ui.view.SafeEmulatorView;

import java.io.File;

import jackpal.androidterm.emulatorview.ColorScheme;
import jackpal.androidterm.emulatorview.TermSession;

public class TerminalFragment extends Fragment {

    private static final ColorScheme DARK_SCHEME = new ColorScheme(
        0xFFBCBEC4, 0xFF141517, 0xFF141517, 0xFF56A8F5);

    private FrameLayout terminalContainer;
    private TextView emptyView;
    private SafeEmulatorView terminalView;
    private EmbeddedShellSession shellSession;
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
    }

    @SuppressLint("SetJavaScriptEnabled")
    private TermSession newEmbeddedSession(@Nullable File workingDirectory) {
        shellSession = new EmbeddedShellSession(workingDirectory);
        return shellSession.getSession();
    }

    private void closeSession() {
        if (terminalView != null && terminalView.getParent() != null) {
            ((ViewGroup) terminalView.getParent()).removeView(terminalView);
        }
        terminalView = null;
        if (shellSession != null) {
            shellSession.destroy();
            shellSession = null;
        }
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
