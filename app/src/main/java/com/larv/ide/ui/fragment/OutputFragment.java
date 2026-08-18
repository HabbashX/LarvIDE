package com.larv.ide.ui.fragment;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.larv.ide.R;
import com.larv.ide.ui.adapter.OutputAdapter;

public class OutputFragment extends Fragment {

    public interface OnInputListener {
        void onInputLine(String line);
    }

    private OutputAdapter adapter;
    private TextView emptyView;
    private RecyclerView recyclerView;
    private View inputBar;
    private EditText inputEdit;
    private OnInputListener inputListener;

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
        inputBar = view.findViewById(R.id.inputBar);
        inputEdit = view.findViewById(R.id.outputInput);
        ImageButton sendButton = view.findViewById(R.id.outputSend);

        adapter = new OutputAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        Runnable submit = this::submitInput;
        sendButton.setOnClickListener(v -> submit.run());
        inputEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                submit.run();
                return true;
            }
            return false;
        });
    }

    private void submitInput() {
        String line = inputEdit.getText().toString();
        inputEdit.setText("");
        if (line.isEmpty()) return;
        if (inputListener != null) {
            inputListener.onInputLine(line);
        }
        addLine(line);
    }

    public void setInputListener(OnInputListener listener) {
        this.inputListener = listener;
    }

    public void showInput() {
        if (inputBar != null) {
            inputBar.setVisibility(View.VISIBLE);
        }
    }

    public void hideInput() {
        if (inputBar != null) {
            inputBar.setVisibility(View.GONE);
        }
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
        hideInput();
        emptyView.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
    }
}