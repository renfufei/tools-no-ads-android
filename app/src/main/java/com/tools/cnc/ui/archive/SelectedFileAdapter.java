package com.tools.cnc.ui.archive;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tools.cnc.R;
import com.tools.cnc.util.FileUtils;

import java.util.ArrayList;
import java.util.List;

public class SelectedFileAdapter extends RecyclerView.Adapter<SelectedFileAdapter.ViewHolder> {

    public interface OnRemoveListener {
        void onRemove(int position);
    }

    private List<CompressEntry> entries = new ArrayList<>();
    private final OnRemoveListener removeListener;

    public SelectedFileAdapter(OnRemoveListener removeListener) {
        this.removeListener = removeListener;
    }

    public void setEntries(List<CompressEntry> entries) {
        this.entries = entries != null ? entries : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_compress_entry, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CompressEntry entry = entries.get(position);
        holder.tvFileName.setText(entry.getDisplayName());
        holder.tvFileSize.setText(FileUtils.formatFileSize(entry.getFileSize()));
        holder.btnRemove.setOnClickListener(v -> {
            if (removeListener != null) {
                removeListener.onRemove(holder.getAdapterPosition());
            }
        });
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvFileName;
        final TextView tvFileSize;
        final ImageButton btnRemove;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvFileName = itemView.findViewById(R.id.tv_file_name);
            tvFileSize = itemView.findViewById(R.id.tv_file_size);
            btnRemove = itemView.findViewById(R.id.btn_remove);
        }
    }
}
