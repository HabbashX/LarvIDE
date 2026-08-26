package com.larv.ide.ui.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.larv.ide.ui.fragment.ErrorsFragment;
import com.larv.ide.ui.fragment.OutputFragment;
import com.larv.ide.ui.fragment.PreviewFragment;
import com.larv.ide.ui.fragment.TerminalFragment;

public class BottomPanelAdapter extends FragmentStateAdapter {

    private final OutputFragment outputFragment = new OutputFragment();
    private final ErrorsFragment errorsFragment = new ErrorsFragment();
    private final PreviewFragment previewFragment = new PreviewFragment();
    private final TerminalFragment terminalFragment = new TerminalFragment();

    public BottomPanelAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 1: return errorsFragment;
            case 2: return previewFragment;
            case 3: return terminalFragment;
            default: return outputFragment;
        }
    }

    @Override
    public int getItemCount() {
        return 4;
    }

    public OutputFragment getOutputFragment() {
        return outputFragment;
    }

    public ErrorsFragment getErrorsFragment() {
        return errorsFragment;
    }

    public PreviewFragment getPreviewFragment() {
        return previewFragment;
    }

    public TerminalFragment getTerminalFragment() {
        return terminalFragment;
    }
}
