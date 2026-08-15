package com.larv.ide.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.larv.ide.R;
import com.larv.ide.ui.adapter.ErrorAdapter;
import com.larv.ide.ui.adapter.OutputAdapter;

public class OutputFragment extends Fragment {

    private OutputAdapter adapter;
    private TextView emptyView;
    private RecyclerView recyclerView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_output, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        emptyView = view.findViewById(R.id.emptyOutput);
        recyclerView = view.findViewById(R.id.outputRecyclerView);
        
        adapter = new OutputAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
    }

    public void addLine(String line) {
        if (emptyView.getVisibility() == View.VISIBLE) {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
        adapter.addLine(line);
        recyclerView.scrollToPosition(adapter.getItemCount() - 1);
    }

    public void clear() {
        adapter.clear();
        emptyView.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
    }
}