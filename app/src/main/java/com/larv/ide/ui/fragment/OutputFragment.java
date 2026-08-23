package com.larv.ide.ui.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.larv.ide.R;
import com.larv.ide.ui.view.SafeEmulatorView;

import java.io.InputStream;
import java.io.OutputStream;

import jackpal.androidterm.emulatorview.ColorScheme;
import jackpal.androidterm.emulatorview.TermSession;

public class OutputFragment extends Fragment {

    private static final ColorScheme DARK_SCHEME = new ColorScheme(
        0xFFBCBEC4,
        0xFF191A1C,
        0xFF191A1C,
        0xFF56A8F5
    );

    private FrameLayout terminalContainer;
    private TextView emptyView;
    private SafeEmulatorView terminalView;
    private TermSession currentSession;

    private InputStream pendingProgramOutput;
    private OutputStream pendingProgramInput;
    private boolean startPending = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_output, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        emptyView = view.findViewById(R.id.emptyOutput);
        terminalContainer = view.findViewById(R.id.terminalContainer);
        if (startPending) {
            startPending = false;
            startProgram(pendingProgramOutput, pendingProgramInput);
        }
    }

    public void startProgram(InputStream programOutput, OutputStream programInput) {
        if (terminalContainer == null) {
            pendingProgramOutput = programOutput;
            pendingProgramInput = programInput;
            startPending = true;
            return;
        }

        if (emptyView != null) {
            emptyView.setVisibility(View.GONE);
        }
        terminalContainer.setVisibility(View.VISIBLE);

        finishCurrentSession();

        TermSession session = new TermSession(false);
        session.setTermIn(programOutput);
        session.setTermOut(programInput);
        session.setDefaultUTF8Mode(true);

        SafeEmulatorView view = new SafeEmulatorView(requireContext(), session, getResources().getDisplayMetrics());
        view.setTextSize(13);
        view.setUseCookedIME(true);
        view.setColorScheme(DARK_SCHEME);

        terminalContainer.addView(view, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        terminalView = view;
        currentSession = session;

        view.requestFocus();
        InputMethodManager imm = (InputMethodManager) requireContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    public void clear() {
        finishCurrentSession();
        releasePendingStreams();
        if (emptyView != null) {
            emptyView.setVisibility(View.VISIBLE);
        }
        if (terminalContainer != null) {
            terminalContainer.setVisibility(View.GONE);
        }
    }

    private void finishCurrentSession() {
        if (terminalView != null && terminalView.getParent() != null) {
            ((ViewGroup) terminalView.getParent()).removeView(terminalView);
        }
        terminalView = null;
        if (currentSession != null) {
            try {
                if (currentSession.isRunning()) {
                    currentSession.finish();
                }
            } catch (Exception ignored) {
            }
            currentSession = null;
        }
    }

    private void releasePendingStreams() {
        pendingProgramOutput = null;
        pendingProgramInput = null;
        startPending = false;
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
        finishCurrentSession();
        releasePendingStreams();
        super.onDestroyView();
    }
}
