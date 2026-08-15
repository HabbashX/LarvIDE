package com.larv.ide.ui.adapter;

import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.recyclerview.widget.RecyclerView;

import com.larv.ide.R;
import com.larv.ide.model.FileNode;

import java.util.ArrayList;
import java.util.List;

public class FileTreeAdapter extends RecyclerView.Adapter<FileTreeAdapter.FileViewHolder> {

    private List<FileNode> visibleNodes = new ArrayList<>();
    private final List<FileNode> rootNodes = new ArrayList<>();
    private final OnFileClickListener listener;

    public interface OnFileClickListener {
        void onFileClick(FileNode node);
        void onFileLongClick(FileNode node);
    }

    public FileTreeAdapter(OnFileClickListener listener) {
        this.listener = listener;
    }

    public void setRootNodes(List<FileNode> rootNodes) {
        this.rootNodes.clear();
        this.rootNodes.addAll(rootNodes);
        refreshVisibleNodes();
    }

    private void refreshVisibleNodes() {
        visibleNodes.clear();
        for (FileNode root : rootNodes) {
            addVisibleNodes(root);
        }
        notifyDataSetChanged();
    }

    private void addVisibleNodes(FileNode node) {
        visibleNodes.add(node);
        if (node.getType() == FileNode.Type.DIRECTORY && node.isExpanded()) {
            for (FileNode child : node.getChildren()) {
                addVisibleNodes(child);
            }
        }
    }

    public void toggleExpansion(FileNode node) {
        if (node.getType() == FileNode.Type.DIRECTORY) {
            node.setExpanded(!node.isExpanded());
            refreshVisibleNodes();
        }
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_file_tree, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        FileNode node = visibleNodes.get(position);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            holder.bind(node);
        }

        holder.itemView.setOnClickListener(v -> listener.onFileClick(node));
        holder.itemView.setOnLongClickListener(v -> {
            listener.onFileLongClick(node);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return visibleNodes.size();
    }

    static class FileViewHolder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView name;
        private final ImageView more;

        FileViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.fileIcon);
            name = itemView.findViewById(R.id.fileName);
            more = itemView.findViewById(R.id.fileMore);
        }

        @RequiresApi(api = Build.VERSION_CODES.M)
        void bind(@NonNull FileNode node) {
            name.setText(node.getName());
            
            int indent = node.getDepth() * 24;
            itemView.setPadding(indent, itemView.getPaddingTop(), 
                itemView.getPaddingRight(), itemView.getPaddingBottom());

            if (node.getType() == FileNode.Type.DIRECTORY) {
                icon.setImageResource(node.isExpanded() ? 
                    android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float);
                icon.setColorFilter(itemView.getContext().getColor(android.R.color.white));
            } else if (node.isJavaFile()) {
                icon.setImageResource(android.R.drawable.ic_menu_report_image);
                icon.setColorFilter(itemView.getContext().getColor(R.color.accent_blue));
            } else {
                icon.setImageResource(android.R.drawable.ic_menu_report_image);
                icon.setColorFilter(itemView.getContext().getColor(R.color.text_secondary));
            }
        }
    }
}