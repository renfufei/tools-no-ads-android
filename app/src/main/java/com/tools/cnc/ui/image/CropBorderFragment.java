package com.tools.cnc.ui.image;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.tools.cnc.R;
import com.tools.cnc.core.image.BorderCropper;
import com.tools.cnc.core.image.ImageCallback;
import com.tools.cnc.databinding.FragmentCropBorderBinding;
import com.tools.cnc.util.PermissionHelper;
import com.tools.cnc.util.StorageHelper;

import java.io.File;
import java.util.Collections;
import java.util.List;

public class CropBorderFragment extends Fragment {

    private FragmentCropBorderBinding binding;
    private File selectedFile;
    private Bitmap previewBitmap;

    private final ActivityResultLauncher<Intent> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null
                        && result.getData().getData() != null) {
                    handleSelectedImage(result.getData().getData());
                }
            });

    private final ActivityResultLauncher<Intent> storagePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (PermissionHelper.hasStoragePermission(requireContext())) {
                    performCrop();
                } else {
                    Toast.makeText(requireContext(),
                            getString(R.string.error) + ": 存储权限被拒绝", Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String[]> legacyPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), results -> {
                if (PermissionHelper.hasStoragePermission(requireContext())) {
                    performCrop();
                } else {
                    Toast.makeText(requireContext(),
                            getString(R.string.error) + ": 存储权限被拒绝", Toast.LENGTH_LONG).show();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentCropBorderBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.btnSelectImage.setOnClickListener(v -> openImagePicker());
        binding.btnCrop.setOnClickListener(v -> performCrop());

        binding.sliderThreshold.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser && previewBitmap != null) {
                showPreviewWithBounds((int) value);
            }
        });
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        imagePickerLauncher.launch(intent);
    }

    private void handleSelectedImage(Uri uri) {
        try {
            String name = StorageHelper.getFileName(requireContext(), uri);
            selectedFile = StorageHelper.copyUriToCache(requireContext(), uri, name);
            previewBitmap = BitmapFactory.decodeFile(selectedFile.getAbsolutePath());
            showPreviewWithBounds((int) binding.sliderThreshold.getValue());
            binding.btnCrop.setEnabled(true);
        } catch (Exception e) {
            Toast.makeText(requireContext(), getString(R.string.error), Toast.LENGTH_SHORT).show();
        }
    }

    private void showPreviewWithBounds(int threshold) {
        if (previewBitmap == null) return;

        BorderCropper cropper = new BorderCropper();
        Rect bounds = cropper.detectContentBounds(previewBitmap, threshold);

        // Draw preview with crop area highlighted
        Bitmap preview = previewBitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(preview);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, paint);

        binding.ivPreview.setImageBitmap(preview);
    }

    private void performCrop() {
        if (selectedFile == null) return;

        int threshold = (int) binding.sliderThreshold.getValue();
        File outputDir = StorageHelper.getImageOutputDir(requireContext());

        // Check if output directory is usable, request permission if not
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            requestStoragePermission();
            return;
        }

        binding.btnCrop.setEnabled(false);

        new BorderCropper().cropBatch(
                Collections.singletonList(selectedFile), outputDir, threshold, new ImageCallback() {
                    @Override
                    public void onProgress(int current, int total, String currentFile) {}

                    @Override
                    public void onComplete(List<File> outputFiles) {
                        requireActivity().runOnUiThread(() -> {
                            binding.btnCrop.setEnabled(true);
                            if (!outputFiles.isEmpty()) {
                                Bitmap result = BitmapFactory.decodeFile(
                                        outputFiles.get(0).getAbsolutePath());
                                binding.ivPreview.setImageBitmap(result);
                                Toast.makeText(requireContext(),
                                        getString(R.string.success), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        requireActivity().runOnUiThread(() -> {
                            binding.btnCrop.setEnabled(true);
                            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            Uri uri = Uri.fromParts("package", requireContext().getPackageName(), null);
            intent.setData(uri);
            storagePermissionLauncher.launch(intent);
        } else {
            legacyPermissionLauncher.launch(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            });
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        if (previewBitmap != null && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
    }
}
