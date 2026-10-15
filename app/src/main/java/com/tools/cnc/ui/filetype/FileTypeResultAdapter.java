package com.tools.cnc.ui.filetype;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tools.cnc.R;
import com.tools.cnc.databinding.ItemImagePreviewBinding;

import java.util.ArrayList;
import java.util.List;

public class FileTypeResultAdapter extends RecyclerView.Adapter<FileTypeResultAdapter.ViewHolder> {

    public static class FileTypeResult {
        public final String fileName;
        public final String detectedMimeType;
        public final String description;
        public final String fileExtension;
        public final String detectedExtension;
        public final boolean matches;

        public FileTypeResult(String fileName, String detectedMimeType, String description,
                              String fileExtension, String detectedExtension, boolean matches) {
            this.fileName = fileName;
            this.detectedMimeType = detectedMimeType;
            this.description = description;
            this.fileExtension = fileExtension;
            this.detectedExtension = detectedExtension;
            this.matches = matches;
        }
    }

    private List<FileTypeResult> results = new ArrayList<>();

    public void setResults(List<FileTypeResult> results) {
        this.results = results;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemImagePreviewBinding binding = ItemImagePreviewBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(results.get(position));
    }

    @Override
    public int getItemCount() { return results.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemImagePreviewBinding binding;

        ViewHolder(ItemImagePreviewBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(FileTypeResult result) {
            binding.tvFileName.setText(result.fileName);
            binding.tvSizeInfo.setText(result.description + " (" + result.detectedMimeType + ")");

            if (result.matches) {
                binding.tvStatus.setText("✓ 匹配");
                binding.tvStatus.setTextColor(
                        binding.getRoot().getContext().getResources().getColor(R.color.match_color));
            } else {
                binding.tvStatus.setText("✗ 不匹配");
                binding.tvStatus.setTextColor(
                        binding.getRoot().getContext().getResources().getColor(R.color.mismatch_color));
            }

            binding.ivThumbnail.setImageResource(R.drawable.ic_file_type);
        }
    }
}
