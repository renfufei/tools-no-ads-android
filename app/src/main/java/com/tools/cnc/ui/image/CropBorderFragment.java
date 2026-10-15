package com.tools.cnc.ui.image;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Removes the status bar, the navigation bar and any solid dark border from an
 * image.
 *
 * <p>The screen is built around a live preview: two amber dashed lines mark the
 * top and bottom ignore bands (drag them directly, or use the sliders), and the
 * red rectangle shows the region that will survive the crop.</p>
 *
 * <p>Detection always runs off the main thread. The preview and the live
 * detection both work on a down-scaled copy of the image; the final crop
 * re-detects at full resolution, so preview geometry and saved output can differ
 * by a pixel or two.</p>
 */
public class CropBorderFragment extends Fragment {

    /** Longest edge used for the on-screen preview and for live detection. */
    private static final int PREVIEW_MAX_DIMENSION = 1280;

    private static final int DEFAULT_IGNORE_PERCENT = 3;
    private static final int DEFAULT_THRESHOLD = 30;
    private static final int DEFAULT_NOISE_PERCENT = 1;
    private static final int MAX_IGNORE_PERCENT = 30;
    private static final long DETECT_DEBOUNCE_MS = 60L;

    private FragmentCropBorderBinding binding;

    private File selectedFile;
    private Bitmap previewBitmap;
    private int originalWidth;
    private int originalHeight;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable detectTask = this::runDetection;
    /** Guards against a stale detection result landing after a newer request. */
    private int detectToken;

    private boolean advancedExpanded;

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
                    toast(getString(R.string.error) + ": 存储权限被拒绝");
                }
            });

    private final ActivityResultLauncher<String[]> legacyPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), results -> {
                if (PermissionHelper.hasStoragePermission(requireContext())) {
                    performCrop();
                } else {
                    toast(getString(R.string.error) + ": 存储权限被拒绝");
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
        binding.btnAutoDetect.setOnClickListener(v -> {
            if (previewBitmap == null) {
                toast(getString(R.string.crop_border_no_image));
                return;
            }
            runAutoDetect();
        });
        binding.btnCrop.setOnClickListener(v -> performCrop());
        binding.btnReset.setOnClickListener(v -> resetToDefaults());

        binding.rowAdvancedToggle.setOnClickListener(v -> toggleAdvanced());

        binding.cropPreview.setOnIgnoreLineChangeListener(new CropPreviewView.OnIgnoreLineChangeListener() {
            @Override
            public void onIgnoreLineDragging(int topInset, int bottomInset) {
                syncSlidersFromPreview();
                updateIgnoreLabels();
                scheduleDetect(DETECT_DEBOUNCE_MS);
            }

            @Override
            public void onIgnoreLineChanged(int topInset, int bottomInset) {
                syncSlidersFromPreview();
                updateIgnoreLabels();
                scheduleDetect(0L);
            }
        });

        setupIgnoreSlider(binding.sliderIgnoreTop, true);
        setupIgnoreSlider(binding.sliderIgnoreBottom, false);

        binding.sliderThreshold.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser) {
                return;
            }
            binding.tvThresholdValue.setText(
                    getString(R.string.crop_border_value_plain, (int) value));
            scheduleDetect(DETECT_DEBOUNCE_MS);
        });

        binding.sliderNoise.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser) {
                return;
            }
            binding.tvNoiseValue.setText(
                    getString(R.string.crop_border_value_percent_only, (int) value));
            scheduleDetect(DETECT_DEBOUNCE_MS);
        });

        showDefaultLabels();
    }

    // -------------------------------------------------------------------------
    // Image selection
    // -------------------------------------------------------------------------

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        imagePickerLauncher.launch(intent);
    }

    private void handleSelectedImage(Uri uri) {
        File cached;
        try {
            String name = StorageHelper.getFileName(requireContext(), uri);
            cached = StorageHelper.copyUriToCache(requireContext(), uri, name);
        } catch (Exception e) {
            toast(getString(R.string.error));
            return;
        }

        BitmapFactory.Options boundsOnly = new BitmapFactory.Options();
        boundsOnly.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(cached.getAbsolutePath(), boundsOnly);
        if (boundsOnly.outWidth <= 0 || boundsOnly.outHeight <= 0) {
            toast(getString(R.string.error));
            return;
        }

        Bitmap decoded = decodePreview(cached);
        if (decoded == null) {
            toast(getString(R.string.error));
            return;
        }

        selectedFile = cached;
        originalWidth = boundsOnly.outWidth;
        originalHeight = boundsOnly.outHeight;
        previewBitmap = decoded;

        binding.cropPreview.setBitmap(previewBitmap);
        binding.tvPreviewHint.setVisibility(View.GONE);
        binding.btnSelectImage.setText(R.string.crop_border_reselect);
        binding.btnCrop.setEnabled(true);

        // Give the user a usable starting point without any manual fiddling.
        resetToDefaults();
    }

    /** Decodes a down-scaled copy of {@code file} for display and live detection. */
    @Nullable
    private Bitmap decodePreview(File file) {
        BitmapFactory.Options boundsOnly = new BitmapFactory.Options();
        boundsOnly.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), boundsOnly);

        int width = boundsOnly.outWidth;
        int height = boundsOnly.outHeight;
        if (width <= 0 || height <= 0) {
            return null;
        }

        int sampleSize = 1;
        while (Math.max(width, height) / sampleSize > PREVIEW_MAX_DIMENSION) {
            sampleSize *= 2;
        }

        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = sampleSize;
        decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), decodeOptions);
    }

    // -------------------------------------------------------------------------
    // Parameter plumbing
    // -------------------------------------------------------------------------

    private BorderCropper.Options readOptions() {
        return new BorderCropper.Options(
                (int) binding.sliderThreshold.getValue(),
                binding.sliderNoise.getValue() / 100f,
                binding.cropPreview.getIgnoreTop(),
                binding.cropPreview.getIgnoreBottom());
    }

    private void setupIgnoreSlider(com.google.android.material.slider.Slider slider, boolean isTop) {
        slider.addOnChangeListener((s, value, fromUser) -> {
            if (!fromUser || previewBitmap == null) {
                return;
            }
            int height = previewBitmap.getHeight();
            int inset = Math.round(height * value / 100f);
            if (isTop) {
                binding.cropPreview.setIgnoreInsets(inset, binding.cropPreview.getIgnoreBottom());
                binding.sliderIgnoreBottom.setValue(
                        percentOf(binding.cropPreview.getIgnoreBottom(), height));
            } else {
                binding.cropPreview.setIgnoreInsets(binding.cropPreview.getIgnoreTop(), inset);
                binding.sliderIgnoreTop.setValue(
                        percentOf(binding.cropPreview.getIgnoreTop(), height));
            }
            updateIgnoreLabels();
            scheduleDetect(DETECT_DEBOUNCE_MS);
        });
    }

    /** Mirrors the preview's ignore bands onto the two sliders. */
    private void syncSlidersFromPreview() {
        if (previewBitmap == null) {
            return;
        }
        int height = previewBitmap.getHeight();
        float top = percentOf(binding.cropPreview.getIgnoreTop(), height);
        float bottom = percentOf(binding.cropPreview.getIgnoreBottom(), height);

        if (binding.sliderIgnoreTop.getValue() != top) {
            binding.sliderIgnoreTop.setValue(top);
        }
        if (binding.sliderIgnoreBottom.getValue() != bottom) {
            binding.sliderIgnoreBottom.setValue(bottom);
        }
    }

    private float percentOf(int pixels, int height) {
        if (height <= 0) {
            return 0f;
        }
        int percent = clamp(Math.round(pixels * 100f / height), 0, MAX_IGNORE_PERCENT);
        return percent;
    }

    private void updateIgnoreLabels() {
        if (previewBitmap == null) {
            return;
        }
        int height = previewBitmap.getHeight();
        int topPx = binding.cropPreview.getIgnoreTop();
        int bottomPx = binding.cropPreview.getIgnoreBottom();

        binding.tvIgnoreTopValue.setText(getString(R.string.crop_border_value_percent,
                Math.round(topPx * 100f / height), topPx));
        binding.tvIgnoreBottomValue.setText(getString(R.string.crop_border_value_percent,
                Math.round(bottomPx * 100f / height), bottomPx));
    }

    private void showDefaultLabels() {
        binding.tvIgnoreTopValue.setText(getString(R.string.crop_border_value_percent,
                DEFAULT_IGNORE_PERCENT, 0));
        binding.tvIgnoreBottomValue.setText(getString(R.string.crop_border_value_percent, 0, 0));
        binding.tvThresholdValue.setText(
                getString(R.string.crop_border_value_plain, DEFAULT_THRESHOLD));
        binding.tvNoiseValue.setText(
                getString(R.string.crop_border_value_percent_only, DEFAULT_NOISE_PERCENT));
    }

    private void toggleAdvanced() {
        advancedExpanded = !advancedExpanded;
        binding.layoutAdvanced.setVisibility(advancedExpanded ? View.VISIBLE : View.GONE);
        binding.ivAdvancedArrow.setRotation(advancedExpanded ? 180f : 0f);
    }

    // -------------------------------------------------------------------------
    // Detection
    // -------------------------------------------------------------------------

    private void scheduleDetect(long delayMs) {
        mainHandler.removeCallbacks(detectTask);
        if (delayMs <= 0L) {
            runDetection();
        } else {
            mainHandler.postDelayed(detectTask, delayMs);
        }
    }

    private void runDetection() {
        final Bitmap bitmap = previewBitmap;
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        final BorderCropper.Options options = readOptions();
        final int token = ++detectToken;

        worker.execute(() -> {
            Rect bounds = new BorderCropper().detectContentBounds(bitmap, options);
            if (bounds == null) {
                // Nothing survived the border scan - fall back to just honouring
                // the ignore bands so the preview still shows something sensible.
                bounds = BorderCropper.scanRegion(bitmap.getWidth(), bitmap.getHeight(), options);
            }
            final Rect result = bounds;

            mainHandler.post(() -> {
                if (token != detectToken || binding == null) {
                    return;
                }
                binding.cropPreview.setContentBounds(result);
                updateSizeInfo(result);
            });
        });
    }

    private void updateSizeInfo(Rect bounds) {
        if (previewBitmap == null || originalHeight <= 0 || previewBitmap.getHeight() <= 0) {
            return;
        }
        float scale = originalHeight / (float) previewBitmap.getHeight();

        int newWidth = Math.round(bounds.width() * scale);
        int newHeight = Math.round(bounds.height() * scale);

        int trimTop = Math.round(bounds.top * scale);
        int trimLeft = Math.round(bounds.left * scale);
        int trimBottom = Math.round((previewBitmap.getHeight() - bounds.bottom) * scale);
        int trimRight = Math.round((previewBitmap.getWidth() - bounds.right) * scale);

        binding.tvSizeInfo.setText(getString(R.string.crop_border_size_info,
                originalWidth, originalHeight, newWidth, newHeight));
        binding.tvTrimInfo.setText(getString(R.string.crop_border_trim_info,
                trimTop, trimBottom, trimLeft, trimRight));
    }

    private void resetToDefaults() {
        if (previewBitmap == null) {
            return;
        }
        binding.sliderThreshold.setValue(DEFAULT_THRESHOLD);
        binding.sliderNoise.setValue(DEFAULT_NOISE_PERCENT);
        runAutoDetect();
    }

    /** Asks the estimator for a status / navigation bar height and pre-fills the sliders. */
    private void runAutoDetect() {
        final Bitmap bitmap = previewBitmap;
        if (bitmap == null) {
            return;
        }
        final BorderCropper.Options base = new BorderCropper.Options(
                (int) binding.sliderThreshold.getValue(),
                binding.sliderNoise.getValue() / 100f, 0, 0);

        binding.btnAutoDetect.setEnabled(false);

        worker.execute(() -> {
            final int[] insets = BorderCropper.estimateInsets(bitmap, base);

            mainHandler.post(() -> {
                if (binding == null) {
                    return;
                }
                binding.btnAutoDetect.setEnabled(true);
                applyInsets(insets[0], insets[1]);
                toast(getString(R.string.crop_border_auto_done, insets[0], insets[1]));
            });
        });
    }

    private void applyInsets(int topPx, int bottomPx) {
        if (previewBitmap == null) {
            return;
        }
        int height = previewBitmap.getHeight();
        int topPercent = clamp(Math.round(topPx * 100f / height), 0, MAX_IGNORE_PERCENT);
        int bottomPercent = clamp(Math.round(bottomPx * 100f / height), 0, MAX_IGNORE_PERCENT);

        binding.cropPreview.setIgnoreInsets(
                Math.round(height * topPercent / 100f),
                Math.round(height * bottomPercent / 100f));

        binding.sliderIgnoreTop.setValue(topPercent);
        binding.sliderIgnoreBottom.setValue(bottomPercent);

        updateIgnoreLabels();
        scheduleDetect(0L);
    }

    // -------------------------------------------------------------------------
    // Cropping
    // -------------------------------------------------------------------------

    private void performCrop() {
        if (selectedFile == null) {
            toast(getString(R.string.crop_border_no_image));
            return;
        }

        File outputDir = StorageHelper.getImageOutputDir(requireContext());
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            requestStoragePermission();
            return;
        }

        // The preview measures the ignore bands against a down-scaled bitmap, so
        // lift them back to original-resolution pixels before cropping.
        float scale = (previewBitmap != null && previewBitmap.getHeight() > 0)
                ? originalHeight / (float) previewBitmap.getHeight()
                : 1f;
        final BorderCropper.Options cropOptions = readOptions().scaledInsets(scale);

        binding.btnCrop.setEnabled(false);
        binding.btnCrop.setText(R.string.crop_border_cropping);

        new BorderCropper().cropBatch(Collections.singletonList(selectedFile), outputDir,
                cropOptions, new ImageCallback() {
                    @Override
                    public void onProgress(int current, int total, String currentFile) {
                    }

                    @Override
                    public void onComplete(List<File> outputFiles) {
                        if (binding == null) {
                            return;
                        }
                        binding.btnCrop.setEnabled(true);
                        binding.btnCrop.setText(R.string.crop_border_save);

                        if (outputFiles.isEmpty()) {
                            toast(getString(R.string.error));
                            return;
                        }
                        toast(getString(R.string.crop_border_saved,
                                outputFiles.get(0).getAbsolutePath()));
                    }

                    @Override
                    public void onError(String message) {
                        if (binding == null) {
                            return;
                        }
                        binding.btnCrop.setEnabled(true);
                        binding.btnCrop.setText(R.string.crop_border_save);
                        toast(message);
                    }
                });
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.fromParts("package", requireContext().getPackageName(), null));
            storagePermissionLauncher.launch(intent);
        } else {
            legacyPermissionLauncher.launch(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            });
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    private void toast(String message) {
        if (!isAdded()) {
            return;
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacks(detectTask);
        // Invalidate any in-flight detection so it cannot touch a torn-down view.
        detectToken++;
        // The preview bitmap is deliberately not recycled here: a detection task
        // may still be reading it. It is small (<= 1280px) and the GC reclaims it.
        previewBitmap = null;
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }
}
