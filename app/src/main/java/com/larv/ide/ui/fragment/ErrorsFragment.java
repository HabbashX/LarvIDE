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
import com.larv.ide.model.Diagnostic;
import com.larv.ide.ui.adapter.ErrorAdapter;

import java.util.List;

public class ErrorsFragment extends Fragment {

    private ErrorAdapter adapter;
    private TextView emptyView;
    private RecyclerView recyclerView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_errors, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        emptyView = view.findViewById(R.id.emptyErrors);
        recyclerView = view.findViewById(R.id.errorsRecyclerView);
        
        adapter = new ErrorAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
    }

    public void setErrors(@NonNull List<Diagnostic> errors) {
        if (errors.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
        adapter.setErrors(errors);
    }
}