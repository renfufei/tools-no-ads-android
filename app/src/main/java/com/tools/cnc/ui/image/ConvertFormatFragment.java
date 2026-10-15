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
import com.tools.cnc.core.image.FormatConverter;
import com.tools.cnc.core.image.ImageCallback;
import com.tools.cnc.databinding.FragmentConvertFormatBinding;
import com.tools.cnc.util.StorageHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ConvertFormatFragment extends Fragment {

    private FragmentConvertFormatBinding binding;
    private ImageAdapter adapter;
    private List<File> selectedFiles = new ArrayList<>();
    private String targetFormat = "jpg";

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
        binding = FragmentConvertFormatBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new ImageAdapter();
        binding.recyclerResults.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerResults.setAdapter(adapter);

        binding.btnSelectImages.setOnClickListener(v -> openImagePicker());
        binding.btnConvert.setOnClickListener(v -> startConvert());

        binding.chipGroupFormat.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chip_jpg) targetFormat = "jpg";
            else if (id == R.id.chip_png) targetFormat = "png";
            else if (id == R.id.chip_webp) targetFormat = "webp";
            else if (id == R.id.chip_bmp) targetFormat = "bmp";
            else if (id == R.id.chip_heic) targetFormat = "heic";
        });

        binding.chipJpg.setChecked(true);
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
        binding.btnConvert.setEnabled(!selectedFiles.isEmpty());
    }

    private void copyToCache(Uri uri) {
        try {
            String name = StorageHelper.getFileName(requireContext(), uri);
            File cached = StorageHelper.copyUriToCache(requireContext(), uri, name);
            selectedFiles.add(cached);
        } catch (Exception e) {
            // Skip
        }
    }

    private void startConvert() {
        if (selectedFiles.isEmpty()) return;

        File outputDir = StorageHelper.getImageOutputDir(requireContext());
        binding.btnConvert.setEnabled(false);

        new FormatConverter().convert(selectedFiles, outputDir, targetFormat, 90, new ImageCallback() {
            @Override
            public void onProgress(int current, int total, String currentFile) {
                requireActivity().runOnUiThread(() -> {
                    binding.tvSelectedCount.setText("转换中: " + current + "/" + total);
                });
            }

            @Override
            public void onComplete(List<File> outputFiles) {
                requireActivity().runOnUiThread(() -> {
                    for (int i = 0; i < outputFiles.size() && i < adapter.getItems().size(); i++) {
                        adapter.updateResult(i, outputFiles.get(i), "✓");
                    }
                    binding.tvSelectedCount.setText("转换完成");
                    binding.btnConvert.setEnabled(true);
                    Toast.makeText(requireContext(), getString(R.string.success), Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String message) {
                requireActivity().runOnUiThread(() -> {
                    binding.btnConvert.setEnabled(true);
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
