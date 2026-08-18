package com.larv.ide.ui.adapter;

import android.content.ClipData;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.larv.ide.R;
import com.larv.ide.model.FileNode;

import java.util.ArrayList;
import java.util.List;

public class FileTreeAdapter extends RecyclerView.Adapter<FileTreeAdapter.FileViewHolder> {

    private final List<FileNode> visibleNodes = new ArrayList<>();
    private final List<FileNode> rootNodes = new ArrayList<>();
    private final OnFileClickListener listener;
    private String selectedPath = null;
    private FileNode draggedNode = null;

    public interface OnFileClickListener {
        void onFileClick(FileNode node);
        void onFileMoreClick(FileNode node, View anchor);
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

    public void collapseAll() {
        collapseRecursive(rootNodes);
        refreshVisibleNodes();
    }

    public void expandAll() {
        expandRecursive(rootNodes);
        refreshVisibleNodes();
    }

    private void collapseRecursive(List<FileNode> nodes) {
        for (FileNode node : nodes) {
            if (node.getType() == FileNode.Type.DIRECTORY) {
                node.setExpanded(false);
            }
        }
    }

    private void expandRecursive(List<FileNode> nodes) {
        for (FileNode node : nodes) {
            if (node.getType() == FileNode.Type.DIRECTORY) {
                node.setExpanded(true);
                expandRecursive(node.getChildren());
            }
        }
    }

    public FileNode findNodeByPath(String path) {
        return findNodeRecursive(rootNodes, path);
    }

    private FileNode findNodeRecursive(List<FileNode> nodes, String path) {
        for (FileNode node : nodes) {
            if (node.getPath().equals(path)) {
                return node;
            }
            FileNode found = findNodeRecursive(node.getChildren(), path);
            if (found != null) return found;
        }
        return null;
    }

    public void expandPath(String path) {
        for (FileNode node : rootNodes) {
            if (path.startsWith(node.getPath())) {
                node.setExpanded(true);
                expandAncestors(node.getChildren(), path);
            }
        }
        refreshVisibleNodes();
    }

    private void expandAncestors(List<FileNode> nodes, String path) {
        for (FileNode node : nodes) {
            if (node.getType() == FileNode.Type.DIRECTORY && path.startsWith(node.getPath())) {
                node.setExpanded(true);
                expandAncestors(node.getChildren(), path);
            }
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
        holder.bind(node);
        holder.itemView.setSelected(node.getPath().equals(selectedPath));

        holder.itemView.setOnClickListener(v -> {
            selectedPath = node.getPath();
            notifyDataSetChanged();
            listener.onFileClick(node);
        });
        holder.itemView.setOnLongClickListener(v -> {
            startDrag(holder.itemView, visibleNodes.get(position));
            return true;
        });
        holder.more.setOnClickListener(v -> listener.onFileMoreClick(node, holder.itemView));
    }

    private void startDrag(View view, FileNode node) {
        draggedNode = node;
        ClipData data = ClipData.newPlainText("larvide_path", node.getPath());
        View.DragShadowBuilder shadow = new View.DragShadowBuilder(view);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            view.startDragAndDrop(data, shadow, node, 0);
        } else {
            view.startDrag(data, shadow, node, 0);
        }
    }

    public FileNode getDraggedNode() {
        return draggedNode;
    }

    public void setDraggedNode(FileNode draggedNode) {
        this.draggedNode = draggedNode;
    }

    @Override
    public int getItemCount() {
        return visibleNodes.size();
    }

    public static class FileViewHolder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView name;
        private final ImageView more;
        private FileNode node;

        public FileViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.fileIcon);
            name = itemView.findViewById(R.id.fileName);
            more = itemView.findViewById(R.id.fileMore);
        }

        public FileNode getNode() {
            return node;
        }

        void bind(@NonNull FileNode node) {
            this.node = node;
            name.setText(node.getName());
            
            int indent = node.getDepth() * 24;
            itemView.setPadding(indent, itemView.getPaddingTop(), 
                itemView.getPaddingRight(), itemView.getPaddingBottom());

            if (node.getType() == FileNode.Type.DIRECTORY) {
                icon.setImageResource(node.isExpanded() ?
                    R.drawable.ic_folder_open : R.drawable.ic_folder);
                icon.clearColorFilter();
            } else if (node.isJavaFile()) {
                icon.setImageResource(R.drawable.ic_java);
                icon.clearColorFilter();
            } else {
                icon.setImageResource(R.drawable.ic_file);
                icon.setColorFilter(ContextCompat.getColor(itemView.getContext(), R.color.text_secondary));
            }
        }
    }
}