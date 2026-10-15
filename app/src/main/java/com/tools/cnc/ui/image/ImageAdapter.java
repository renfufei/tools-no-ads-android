package com.tools.cnc.ui.image;

import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tools.cnc.databinding.ItemImagePreviewBinding;
import com.tools.cnc.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ImageAdapter extends RecyclerView.Adapter<ImageAdapter.ViewHolder> {

    public static class ImageResult {
        public final File inputFile;
        public File outputFile;
        public String status;

        public ImageResult(File inputFile) {
            this.inputFile = inputFile;
            this.status = "";
        }
    }

    private final List<ImageResult> items = new ArrayList<>();

    public void setItems(List<File> files) {
        items.clear();
        for (File f : files) {
            items.add(new ImageResult(f));
        }
        notifyDataSetChanged();
    }

    public void updateResult(int index, File outputFile, String status) {
        if (index >= 0 && index < items.size()) {
            items.get(index).outputFile = outputFile;
            items.get(index).status = status;
            notifyItemChanged(index);
        }
    }

    public List<ImageResult> getItems() { return items; }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemImagePreviewBinding binding = ItemImagePreviewBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ImageResult item = items.get(position);
        holder.bind(item);
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemImagePreviewBinding binding;

        ViewHolder(ItemImagePreviewBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(ImageResult item) {
            binding.tvFileName.setText(item.inputFile.getName());

            // Load thumbnail efficiently
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 8;
            try {
                binding.ivThumbnail.setImageBitmap(
                        BitmapFactory.decodeFile(item.inputFile.getAbsolutePath(), opts));
            } catch (Exception e) {
                // Ignore thumbnail loading errors
            }

            String sizeInfo = FileUtils.formatFileSize(item.inputFile.length());
            if (item.outputFile != null) {
                sizeInfo += " → " + FileUtils.formatFileSize(item.outputFile.length());
            }
            binding.tvSizeInfo.setText(sizeInfo);
            binding.tvStatus.setText(item.status);
        }
    }
}
