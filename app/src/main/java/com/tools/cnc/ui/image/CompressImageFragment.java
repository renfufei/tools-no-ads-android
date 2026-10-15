package com.tools.cnc.ui.image;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.tools.cnc.R;
import com.tools.cnc.core.image.ImageCallback;
import com.tools.cnc.core.image.ImageCompressor;
import com.tools.cnc.databinding.FragmentCompressImageBinding;
import com.tools.cnc.util.StorageHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CompressImageFragment extends Fragment {

    private FragmentCompressImageBinding binding;
    private ImageAdapter adapter;
    private List<File> selectedFiles = new ArrayList<>();

    private final ActivityResultLauncher<Intent> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleSelectedImages(result.getData());
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentCompressImageBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new ImageAdapter();
        binding.recyclerResults.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerResults.setAdapter(adapter);

        binding.btnSelectImages.setOnClickListener(v -> openImagePicker());
        binding.btnStartCompress.setOnClickListener(v -> startCompress());
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        imagePickerLauncher.launch(intent);
    }

    private void handleSelectedImages(Intent data) {
        selectedFiles.clear();
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                copyToCache(uri);
            }
        } else if (data.getData() != null) {
            copyToCache(data.getData());
        }

        adapter.setItems(selectedFiles);
        binding.tvSelectedCount.setText(selectedFiles.size() + " 张图片已选择");
        binding.btnStartCompress.setEnabled(!selectedFiles.isEmpty());
    }

    private void copyToCache(Uri uri) {
        try {
            String name = StorageHelper.getFileName(requireContext(), uri);
            File cached = StorageHelper.copyUriToCache(requireContext(), uri, name);
            selectedFiles.add(cached);
        } catch (Exception e) {
            // Skip files that can't be copied
        }
    }

    private void startCompress() {
        if (selectedFiles.isEmpty()) return;

        int quality = (int) binding.sliderQuality.getValue();
        int scale = (int) binding.sliderScale.getValue();
        File outputDir = StorageHelper.getImageOutputDir(requireContext());

        binding.btnStartCompress.setEnabled(false);

        new ImageCompressor().compress(selectedFiles, outputDir, quality, scale, new ImageCallback() {
            @Override
            public void onProgress(int current, int total, String currentFile) {
                requireActivity().runOnUiThread(() -> {
                    String status = current + "/" + total;
                    binding.tvSelectedCount.setText("处理中: " + status);
                });
            }

            @Override
            public void onComplete(List<File> outputFiles) {
                requireActivity().runOnUiThread(() -> {
                    for (int i = 0; i < outputFiles.size() && i < adapter.getItems().size(); i++) {
                        adapter.updateResult(i, outputFiles.get(i), "✓");
                    }
                    binding.tvSelectedCount.setText("压缩完成");
                    binding.btnStartCompress.setEnabled(true);
                    Toast.makeText(requireContext(), getString(R.string.success), Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String message) {
                requireActivity().runOnUiThread(() -> {
                    binding.btnStartCompress.setEnabled(true);
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
