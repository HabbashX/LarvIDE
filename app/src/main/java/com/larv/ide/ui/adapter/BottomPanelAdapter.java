package com.larv.ide.ui.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.larv.ide.ui.fragment.ErrorsFragment;
import com.larv.ide.ui.fragment.OutputFragment;
import com.larv.ide.ui.fragment.PreviewFragment;

public class BottomPanelAdapter extends FragmentStateAdapter {

    private final OutputFragment outputFragment = new OutputFragment();
    private final ErrorsFragment errorsFragment = new ErrorsFragment();
    private final PreviewFragment previewFragment = new PreviewFragment();

    public BottomPanelAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 1: return errorsFragment;
            case 2: return previewFragment;
            default: return outputFragment;
        }
    }

    @Override
    public int getItemCount() {
        return 3;
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
}
