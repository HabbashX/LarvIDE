package com.larv.ide.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.larv.ide.R;
import com.larv.ide.infrastructure.model.Diagnostic;

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
        private final TextView severity;
        private final TextView message;
        private final TextView location;

        ErrorViewHolder(@NonNull View itemView) {
            super(itemView);
            severity = itemView.findViewById(R.id.errorSeverity);
            message = itemView.findViewById(R.id.errorMessage);
            location = itemView.findViewById(R.id.errorLocation);
        }

        void bind(@NonNull Diagnostic error) {
            Diagnostic.Severity s = error.getSeverity();
            int color;
            String letter;
            if (s == Diagnostic.Severity.ERROR) {
                color = androidx.core.content.ContextCompat.getColor(
                    itemView.getContext(), R.color.error);
                letter = "E";
            } else if (s == Diagnostic.Severity.WARNING) {
                color = androidx.core.content.ContextCompat.getColor(
                    itemView.getContext(), R.color.warning);
                letter = "W";
            } else if (s == Diagnostic.Severity.WEAK_WARNING) {
                color = androidx.core.content.ContextCompat.getColor(
                    itemView.getContext(), R.color.text_secondary);
                letter = "w";
            } else {
                color = androidx.core.content.ContextCompat.getColor(
                    itemView.getContext(), R.color.ide_accent);
                letter = "I";
            }
            severity.setText(letter);
            severity.setTextColor(color);
            message.setText(error.getMessage());
            message.setTextColor(color);
            location.setText(error.getShortLocation());
        }
    }
}
