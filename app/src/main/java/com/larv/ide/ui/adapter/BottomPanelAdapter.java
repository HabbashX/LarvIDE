package com.larv.ide.ui.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.larv.ide.ui.fragment.ErrorsFragment;
import com.larv.ide.ui.fragment.OutputFragment;

public class BottomPanelAdapter extends FragmentStateAdapter {

    private final OutputFragment outputFragment = new OutputFragment();
    private final ErrorsFragment errorsFragment = new ErrorsFragment();

    public BottomPanelAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return position == 0 ? outputFragment : errorsFragment;
    }

    @Override
    public int getItemCount() {
        return 2;
    }

    public OutputFragment getOutputFragment() {
        return outputFragment;
    }

    public ErrorsFragment getErrorsFragment() {
        return errorsFragment;
    }
}
