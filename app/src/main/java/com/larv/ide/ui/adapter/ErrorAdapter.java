package com.larv.ide.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.larv.ide.R;
import com.larv.ide.model.Diagnostic;

import java.util.ArrayList;
import java.util.List;

public class ErrorAdapter extends RecyclerView.Adapter<ErrorAdapter.ErrorViewHolder> {

    private List<Diagnostic> errors = new ArrayList<>();

    public void setErrors(List<Diagnostic> errors) {
        this.errors = errors;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ErrorViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_error, parent, false);
        return new ErrorViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ErrorViewHolder holder, int position) {
        Diagnostic error = errors.get(position);
        holder.bind(error);
    }

    @Override
    public int getItemCount() {
        return errors.size();
    }

    static class ErrorViewHolder extends RecyclerView.ViewHolder {
        private final TextView message;
        private final TextView location;

        ErrorViewHolder(@NonNull View itemView) {
            super(itemView);
            message = itemView.findViewById(R.id.errorMessage);
            location = itemView.findViewById(R.id.errorLocation);
        }

        void bind(@NonNull Diagnostic error) {
            message.setText(error.getMessage());
            location.setText(error.getShortLocation());
        }
    }
}