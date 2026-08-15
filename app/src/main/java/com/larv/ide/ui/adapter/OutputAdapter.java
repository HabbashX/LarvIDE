package com.larv.ide.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.larv.ide.R;

import java.util.ArrayList;
import java.util.List;

public class OutputAdapter extends RecyclerView.Adapter<OutputAdapter.OutputViewHolder> {

    private List<String> lines = new ArrayList<>();

    public void addLine(String line) {
        lines.add(line);
        notifyItemInserted(lines.size() - 1);
    }

    public void clear() {
        lines.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public OutputViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_output, parent, false);
        return new OutputViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull OutputViewHolder holder, int position) {
        holder.bind(lines.get(position));
    }

    @Override
    public int getItemCount() {
        return lines.size();
    }

    static class OutputViewHolder extends RecyclerView.ViewHolder {
        private final TextView text;

        OutputViewHolder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.outputText);
        }

        void bind(String line) {
            text.setText(line);
        }
    }
}