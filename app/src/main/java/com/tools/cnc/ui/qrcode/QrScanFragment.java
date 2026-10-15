package com.tools.cnc.ui.qrcode;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.common.util.concurrent.ListenableFuture;
import com.tools.cnc.R;
import com.tools.cnc.core.qrcode.QRCodeDecoder;
import com.tools.cnc.databinding.FragmentQrScanBinding;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * QR live scan: CameraX preview + ImageAnalysis decoding, plus gallery picking.
 */
public class QrScanFragment extends Fragment implements ImageAnalysis.Analyzer {

    private FragmentQrScanBinding binding;
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** Set while a frame is being decoded, or while a result dialog blocks scanning. */
    private final java.util.concurrent.atomic.AtomicBoolean decoding =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private ProcessCameraProvider cameraProvider;
    private boolean cameraGranted = false;

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                cameraGranted = granted;
                updatePermissionUi();
                if (granted) {
                    startCamera();
                }
            });

    private final ActivityResultLauncher<Intent> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK
                        && result.getData() != null && result.getData().getData() != null) {
                    decodeFromGallery(result.getData().getData());
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentQrScanBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        cameraGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        updatePermissionUi();
        if (cameraGranted) {
            startCamera();
        }

        binding.btnGrantCamera.setOnClickListener(v ->
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA));
        binding.btnGallery.setOnClickListener(v -> openGallery());
    }

    private void updatePermissionUi() {
        if (binding == null) {
            return;
        }
        binding.layoutPermission.setVisibility(cameraGranted ? View.GONE : View.VISIBLE);
        binding.viewfinder.setVisibility(cameraGranted ? View.VISIBLE : View.GONE);
        binding.tvScanHint.setVisibility(cameraGranted ? View.VISIBLE : View.GONE);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(requireContext());
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                if (binding == null) {
                    return;
                }
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(decodeExecutor, this);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(getViewLifecycleOwner(),
                        CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception e) {
                Toast.makeText(requireContext(), R.string.error, Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        galleryLauncher.launch(Intent.createChooser(intent, getString(R.string.qr_pick_gallery)));
    }

    private void decodeFromGallery(Uri uri) {
        final Context appContext = requireContext().getApplicationContext();
        decodeExecutor.execute(() -> {
            String text = null;
            try {
                text = QRCodeDecoder.decodeUri(appContext, uri);
            } catch (Exception ignored) {
            }
            final String result = text;
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                if (result != null) {
                    showResult(result);
                } else {
                    Toast.makeText(requireContext(), R.string.qr_not_found,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        if (!decoding.compareAndSet(false, true)) {
            image.close();
            return;
        }
        decodeExecutor.execute(() -> {
            String text = null;
            try {
                text = decodeFrame(image);
            } catch (Exception ignored) {
            } finally {
                image.close();
            }
            final String result = text;
            mainHandler.post(() -> {
                if (result != null && binding != null && isAdded()) {
                    vibrate();
                    showResult(result);
                    // Keep decoding=true while the result dialog blocks scanning;
                    // QrResultHelper.onDismiss resets it.
                } else {
                    decoding.set(false);
                }
            });
        });
    }

    private String decodeFrame(ImageProxy image) throws Exception {
        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        ByteBuffer buffer = yPlane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();

        byte[] luminance = new byte[width * height];
        if (pixelStride == 1 && rowStride == width) {
            buffer.get(luminance);
        } else if (pixelStride == 1) {
            for (int row = 0; row < height; row++) {
                buffer.position(row * rowStride);
                buffer.get(luminance, row * width, width);
            }
        } else {
            for (int row = 0; row < height; row++) {
                int base = row * rowStride;
                for (int col = 0; col < width; col++) {
                    luminance[row * width + col] = buffer.get(base + col * pixelStride);
                }
            }
        }
        return QRCodeDecoder.decodeLuminance(luminance, width, height);
    }

    private void showResult(String text) {
        decoding.set(true);
        QrResultHelper.show(requireContext(), text, () -> decoding.set(false));
    }

    private void vibrate() {
        Vibrator vibrator =
                (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(80,
                    VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(80);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        decodeExecutor.shutdown();
    }
}
