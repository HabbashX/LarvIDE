package com.larv.ide.ui.adapter;

import android.content.ClipData;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.larv.ide.R;
import com.larv.ide.infrastructure.model.FileNode;
import com.larv.ide.ui.view.FileTreeItemView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * IntelliJ-like project view: disclosure arrows, per-extension icons,
 * indentation guides, a current-file highlight, live file search and
 * folders-first / alphabetical sorting.
 */
public class FileTreeAdapter extends RecyclerView.Adapter<FileTreeAdapter.FileViewHolder> {

    private static final int SORT_FOLDERS_FIRST = 0;
    private static final int SORT_ALPHABETICAL = 1;

    private final android.content.Context context;
    private final List<FileNode> visibleNodes = new ArrayList<>();
    private final List<FileNode> rootNodes = new ArrayList<>();
    private final OnFileClickListener listener;
    private String selectedPath = null;
    private String filter = "";
    private int sortMode = SORT_FOLDERS_FIRST;
    private FileNode draggedNode = null;

    public interface OnFileClickListener {
        void onFileClick(FileNode node);
        void onFileMoreClick(FileNode node, View anchor);
    }

    public FileTreeAdapter(android.content.Context context, OnFileClickListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setRootNodes(List<FileNode> rootNodes) {
        this.rootNodes.clear();
        this.rootNodes.addAll(rootNodes);
        sortTree(this.rootNodes);
        refreshVisibleNodes();
    }

    /* ------------------------------------------------------------------ data */

    private void refreshVisibleNodes() {
        visibleNodes.clear();
        String query = filter.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            for (FileNode root : rootNodes) addVisibleNodes(root);
        } else {
            for (FileNode root : rootNodes) addFilteredNodes(root, query);
        }
        notifyDataSetChanged();
    }

    private void addVisibleNodes(FileNode node) {
        visibleNodes.add(node);
        if (node.getType() == FileNode.Type.DIRECTORY && node.isExpanded()) {
            for (FileNode child : node.getChildren()) addVisibleNodes(child);
        }
    }

    /** Show matches plus the folders needed to reach them. */
    private void addFilteredNodes(FileNode node, String query) {
        boolean selfMatches = node.getName().toLowerCase(Locale.ROOT).contains(query);
        boolean childMatches = false;
        if (node.getType() == FileNode.Type.DIRECTORY) {
            for (FileNode child : node.getChildren()) {
                if (subtreeMatches(child, query)) {
                    childMatches = true;
                    break;
                }
            }
        }
        if (!selfMatches && !childMatches) return;
        visibleNodes.add(node);
        if (node.getType() == FileNode.Type.DIRECTORY) {
            // A directly-matching folder shows its whole subtree for context.
            if (selfMatches) {
                for (FileNode child : node.getChildren()) addAllDescendants(child);
            } else {
                for (FileNode child : node.getChildren()) addFilteredNodes(child, query);
            }
        }
    }

    private boolean subtreeMatches(FileNode node, String query) {
        if (node.getName().toLowerCase(Locale.ROOT).contains(query)) return true;
        for (FileNode child : node.getChildren()) {
            if (subtreeMatches(child, query)) return true;
        }
        return false;
    }

    private void addAllDescendants(FileNode node) {
        visibleNodes.add(node);
        for (FileNode child : node.getChildren()) addAllDescendants(child);
    }

    /* --------------------------------------------------------------- actions */

    public void setFilter(String filter) {
        this.filter = filter == null ? "" : filter;
        refreshVisibleNodes();
    }

    public String getFilter() {
        return filter;
    }

    /** @return the new sort mode's short label, for the toolbar tooltip. */
    public String toggleSortMode() {
        sortMode = sortMode == SORT_FOLDERS_FIRST ? SORT_ALPHABETICAL : SORT_FOLDERS_FIRST;
        sortTree(rootNodes);
        refreshVisibleNodes();
        return sortMode == SORT_FOLDERS_FIRST ? "Folders first" : "Alphabetical";
    }

    private void sortTree(List<FileNode> nodes) {
        Comparator<FileNode> comparator = sortMode == SORT_FOLDERS_FIRST
            ? (a, b) -> {
                if (a.getType() != b.getType()) {
                    return a.getType() == FileNode.Type.DIRECTORY ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
            : (a, b) -> a.getName().compareToIgnoreCase(b.getName());
        Collections.sort(nodes, comparator);
        for (FileNode node : nodes) {
            if (node.getType() == FileNode.Type.DIRECTORY) sortTree(node.getChildren());
        }
    }

    public void toggleExpansion(@NonNull FileNode node) {
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

    private void collapseRecursive(@NonNull List<FileNode> nodes) {
        for (FileNode node : nodes) {
            if (node.getType() == FileNode.Type.DIRECTORY) {
                node.setExpanded(false);
                collapseRecursive(node.getChildren());
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
            if (node.getPath().equals(path)) return node;
            FileNode found = findNodeRecursive(node.getChildren(), path);
            if (found != null) return found;
        }
        return null;
    }

    public void setSelectedPath(String path) {
        String previous = selectedPath;
        selectedPath = path;
        int prev = indexOfPath(previous);
        int cur = indexOfPath(selectedPath);
        if (prev >= 0) notifyItemChanged(prev);
        if (cur >= 0) notifyItemChanged(cur);
    }

    /** Expand ancestors of {@code path} and return its visible-row position. */
    public int revealPath(String path) {
        if (path == null || path.isEmpty()) return -1;
        for (FileNode node : rootNodes) {
            if (path.startsWith(node.getPath())) {
                node.setExpanded(true);
                expandAncestors(node.getChildren(), path);
            }
        }
        refreshVisibleNodes();
        return indexOfPath(path);
    }

    private void expandAncestors(List<FileNode> nodes, String path) {
        for (FileNode node : nodes) {
            if (node.getType() == FileNode.Type.DIRECTORY && path.startsWith(node.getPath())) {
                node.setExpanded(true);
                expandAncestors(node.getChildren(), path);
            }
        }
    }

    public void expandPath(String path) {
        revealPath(path);
    }

    public int getPositionForPath(String path) {
        return indexOfPath(path);
    }

    /* -------------------------------------------------------------- adapter */

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
        holder.bind(node, filter, selectedPath);
    }

    private void handleClick(FileNode node) {
        selectedPath = node.getPath();
        listener.onFileClick(node);
        int pos = indexOfPath(selectedPath);
        if (pos >= 0) notifyItemChanged(pos);
    }

    @Nullable
    private FileNode nodeAt(int position) {
        return position >= 0 && position < visibleNodes.size()
            ? visibleNodes.get(position) : null;
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

    @Override
    public long getItemId(int position) {
        return visibleNodes.get(position).getPath().hashCode();
    }

    private int indexOfPath(String path) {
        if (path == null) return -1;
        for (int i = 0; i < visibleNodes.size(); i++) {
            if (visibleNodes.get(i).getPath().equals(path)) return i;
        }
        return -1;
    }

    /* ------------------------------------------------------------- viewholder */

    public class FileViewHolder extends RecyclerView.ViewHolder {
        private final FileTreeItemView row;
        private final ImageView chevron;
        private final ImageView icon;
        private final TextView name;
        private final ImageView more;
        private FileNode node;

        public FileViewHolder(@NonNull View itemView) {
            super(itemView);
            row = (FileTreeItemView) itemView;
            chevron = itemView.findViewById(R.id.fileChevron);
            icon = itemView.findViewById(R.id.fileIcon);
            name = itemView.findViewById(R.id.fileName);
            more = itemView.findViewById(R.id.fileMore);

            itemView.setOnClickListener(v -> {
                FileNode n = nodeAt(getBindingAdapterPosition());
                if (n != null) handleClick(n);
            });
            itemView.setOnLongClickListener(v -> {
                FileNode n = nodeAt(getBindingAdapterPosition());
                if (n == null) return false;
                startDrag(itemView, n);
                return true;
            });
            chevron.setOnClickListener(v -> {
                FileNode n = nodeAt(getBindingAdapterPosition());
                if (n != null && n.getType() == FileNode.Type.DIRECTORY) toggleExpansion(n);
            });
            more.setOnClickListener(v -> {
                FileNode n = nodeAt(getBindingAdapterPosition());
                if (n != null) listener.onFileMoreClick(n, itemView);
            });
        }

        public FileNode getNode() {
            return node;
        }

        void bind(@NonNull FileNode node, String query, String selected) {
            this.node = node;
            row.setDepth(node.getDepth());
            itemView.setSelected(node.getPath().equals(selected));

            boolean directory = node.getType() == FileNode.Type.DIRECTORY;
            chevron.setVisibility(directory ? View.VISIBLE : View.INVISIBLE);
            chevron.setRotation(directory && node.isExpanded() ? 90f : 0f);
            applyIcon(icon, node, directory);
            name.setText(highlight(node.getName(), query));
        }
    }

    private void applyIcon(ImageView icon, FileNode node, boolean directory) {
        if (directory) {
            icon.setImageResource(node.isExpanded()
                ? R.drawable.ic_folder_open : R.drawable.ic_folder);
            icon.clearColorFilter();
            return;
        }
        String lower = node.getName().toLowerCase(Locale.ROOT);
        int res = R.drawable.ic_file;
        int color = R.color.text_secondary;
        if (lower.endsWith(".java")) { res = R.drawable.ic_java; color = R.color.ide_accent; }
        else if (lower.endsWith(".py")) { res = R.drawable.ic_lang_python; color = R.color.success; }
        else if (lower.endsWith(".js") || lower.endsWith(".jsx")
            || lower.endsWith(".ts") || lower.endsWith(".tsx")) {
            res = R.drawable.ic_lang_javascript; color = R.color.warning;
        }
        else if (lower.endsWith(".c") || lower.endsWith(".cc")
            || lower.endsWith(".cpp") || lower.endsWith(".h")
            || lower.endsWith(".hpp")) { res = R.drawable.ic_lang_cpp; color = R.color.ide_accent; }
        else if (lower.endsWith(".go")) { res = R.drawable.ic_lang_go; color = R.color.success; }
        else if (lower.endsWith(".rs")) { res = R.drawable.ic_lang_rust; color = R.color.error; }
        else if (lower.endsWith(".rb")) { res = R.drawable.ic_lang_ruby; color = R.color.error; }
        else if (lower.endsWith(".php")) { res = R.drawable.ic_lang_php; color = R.color.ide_accent; }
        else if (lower.endsWith(".lua")) { res = R.drawable.ic_lang_lua; color = R.color.success; }
        else if (lower.endsWith(".pl") || lower.endsWith(".pm")) {
            res = R.drawable.ic_lang_perl; color = R.color.warning;
        }
        icon.setImageResource(res);
        if (res == R.drawable.ic_file) {
            icon.setColorFilter(ContextCompat.getColor(icon.getContext(), color));
        } else {
            icon.clearColorFilter();
        }
    }

    private CharSequence highlight(String text, String query) {
        if (query == null || query.isEmpty()) return text;
        int at = text.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (at < 0) return text;
        SpannableString spannable = new SpannableString(text);
        spannable.setSpan(new StyleSpan(Typeface.BOLD), at, at + query.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int matchColor = ContextCompat.getColor(context, R.color.primary_light);
        spannable.setSpan(new BackgroundColorSpan((matchColor & 0x00FFFFFF) | 0x55000000),
            at, at + query.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }
}
