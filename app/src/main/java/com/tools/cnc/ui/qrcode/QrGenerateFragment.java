package com.tools.cnc.ui.qrcode;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.google.zxing.WriterException;
import com.tools.cnc.R;
import com.tools.cnc.core.qrcode.QRCodeGenerator;
import com.tools.cnc.databinding.FragmentQrGenerateBinding;
import com.tools.cnc.util.PermissionHelper;
import com.tools.cnc.util.StorageHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QrGenerateFragment extends Fragment {

    private static final int QR_SIZE_PX = 600;

    private FragmentQrGenerateBinding binding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Bitmap generatedBitmap;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentQrGenerateBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.btnGenerate.setOnClickListener(v -> generate());
        binding.btnPasteClipboard.setOnClickListener(v -> pasteFromClipboard());
        binding.btnSave.setOnClickListener(v -> saveToLocal());
        binding.btnShare.setOnClickListener(v -> share());

        observeWindowFocus();
    }

    private void pasteFromClipboard() {
        if (binding == null) {
            return;
        }
        String text = readClipboardText();
        if (text == null || text.isEmpty()) {
            Toast.makeText(requireContext(), R.string.qr_clipboard_empty,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        binding.etContent.setText(text);
        binding.etContent.setSelection(text.length());
    }

    /** Returns the clipboard text, or null when the clipboard has no text. */
    @Nullable
    private String readClipboardText() {
        ClipboardManager cm = (ClipboardManager)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                || cm.getPrimaryClip().getItemCount() == 0) {
            return null;
        }
        CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(requireContext());
        return text == null ? null : text.toString();
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePasteButtonVisibility();
    }

    /** Re-check once the window gains input focus (clipboard reads need focus on API 29+). */
    private void observeWindowFocus() {
        requireView().getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (hasFocus) {
                updatePasteButtonVisibility();
            }
        });
    }

    /** Only show the paste button when the clipboard actually holds text. */
    private void updatePasteButtonVisibility() {
        if (binding == null) {
            return;
        }
        String text = readClipboardText();
        binding.btnPasteClipboard.setVisibility(
                (text == null || text.isEmpty()) ? View.GONE : View.VISIBLE);
    }

    private void generate() {
        if (binding == null) {
            return;
        }
        String content = binding.etContent.getText() == null ? ""
                : binding.etContent.getText().toString().trim();
        if (content.isEmpty()) {
            Toast.makeText(requireContext(), R.string.qr_content_empty,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        binding.btnGenerate.setEnabled(false);
        executor.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = QRCodeGenerator.encode(content, QR_SIZE_PX);
            } catch (WriterException ignored) {
            }
            final Bitmap result = bitmap;
            mainHandler.post(() -> {
                if (binding != null) {
                    binding.btnGenerate.setEnabled(true);
                }
                if (result == null) {
                    if (isAdded()) {
                        Toast.makeText(requireContext(), R.string.qr_generate_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                } else {
                    showResult(result);
                }
            });
        });
    }

    private void showResult(Bitmap bitmap) {
        if (binding == null) {
            return;
        }
        generatedBitmap = bitmap;
        binding.ivQrcode.setImageBitmap(bitmap);
        binding.cardPreview.setVisibility(View.VISIBLE);
        binding.layoutActions.setVisibility(View.VISIBLE);
    }

    private void saveToLocal() {
        if (binding == null || generatedBitmap == null) {
            return;
        }
        if (!PermissionHelper.hasStoragePermission(requireContext())) {
            Toast.makeText(requireContext(), R.string.qr_storage_permission_msg,
                    Toast.LENGTH_LONG).show();
            PermissionHelper.requestStoragePermission(requireActivity());
            return;
        }
        final Context appContext = requireContext().getApplicationContext();
        final Bitmap bitmap = generatedBitmap;
        executor.execute(() -> {
            String fileName = "qr_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".png";
            File outputFile = new File(StorageHelper.getQrCodeOutputDir(appContext), fileName);
            boolean success = false;
            try (FileOutputStream out = new FileOutputStream(outputFile)) {
                success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.flush();
            } catch (Exception ignored) {
            }
            final boolean ok = success;
            final String path = outputFile.getAbsolutePath();
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                if (ok) {
                    Toast.makeText(requireContext(),
                            getString(R.string.qr_saved, path), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(requireContext(), R.string.qr_save_failed,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void share() {
        if (binding == null || generatedBitmap == null) {
            return;
        }
        final Context appContext = requireContext().getApplicationContext();
        executor.execute(() -> {
            File shareFile = new File(appContext.getCacheDir(), "qr_share.png");
            boolean success = false;
            try (FileOutputStream out = new FileOutputStream(shareFile)) {
                success = generatedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.flush();
            } catch (Exception ignored) {
            }
            if (!success) {
                mainHandler.post(() -> {
                    if (isAdded()) {
                        Toast.makeText(requireContext(), R.string.qr_save_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
            Uri uri = FileProvider.getUriForFile(appContext,
                    appContext.getPackageName() + ".fileprovider", shareFile);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(intent, getString(R.string.qr_share));
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(chooser);
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
