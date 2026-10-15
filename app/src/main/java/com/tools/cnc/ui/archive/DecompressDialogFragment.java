package com.tools.cnc.ui.archive;

import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.tools.cnc.R;
import com.tools.cnc.core.archive.ArchiveCallback;
import com.tools.cnc.core.archive.ArchiveManager;
import com.tools.cnc.databinding.DialogDecompressBinding;
import com.tools.cnc.util.FileUtils;

import java.io.File;

public class DecompressDialogFragment extends DialogFragment {

    private static final String ARG_FILE_PATH = "file_path";
    private DialogDecompressBinding binding;
    private OnDecompressListener listener;
    private AlertDialog dialog;

    public interface OnDecompressListener {
        void onDecompressComplete();
    }

    public static DecompressDialogFragment newInstance(String filePath) {
        DecompressDialogFragment fragment = new DecompressDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_FILE_PATH, filePath);
        fragment.setArguments(args);
        return fragment;
    }

    public void setOnDecompressListener(OnDecompressListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        binding = DialogDecompressBinding.inflate(getLayoutInflater());

        String filePath = getArguments().getString(ARG_FILE_PATH);
        File file = new File(filePath);

        binding.tvFileInfo.setText(file.getName() + " (" + FileUtils.formatFileSize(file.length()) + ")");
        binding.tvOutputHint.setText(getString(R.string.decompress_output_hint,
                getOutputDir(file).getAbsolutePath()));

        // Check if file is encrypted
        boolean encrypted = new ArchiveManager().isEncrypted(file);
        binding.layoutPassword.setVisibility(encrypted ? View.VISIBLE : View.GONE);

        dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.decompress)
                .setView(binding.getRoot())
                .setPositiveButton(R.string.confirm, null) // Set null to prevent auto-dismiss
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                startDecompress(file);
            });
        });

        return dialog;
    }

    /** Output goes next to the archive, inside a folder named after it. */
    private static File getOutputDir(File archiveFile) {
        return new File(archiveFile.getParentFile(),
                FileUtils.getNameWithoutExtension(archiveFile.getName()));
    }

    private void startDecompress(File file) {
        String password = null;
        if (binding.layoutPassword.getVisibility() == View.VISIBLE) {
            password = binding.etPassword.getText().toString();
        }

        // Output to a directory with the same name as the archive
        File outputDir = getOutputDir(file);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        ProgressDialogFragment progressDialog = ProgressDialogFragment.newInstance(
                getString(R.string.decompressing));
        progressDialog.show(getParentFragmentManager(), "progress");

        new ArchiveManager().decompress(file, outputDir, password, new ArchiveCallback() {
            @Override
            public void onProgress(int percentage, String currentFile) {
                if (progressDialog.isAdded()) {
                    progressDialog.updateProgress(percentage, currentFile);
                }
            }

            @Override
            public void onSuccess(String outputPath) {
                if (progressDialog.isAdded()) {
                    progressDialog.dismiss();
                }
                if (dialog != null) {
                    dialog.dismiss();
                }
                if (listener != null) listener.onDecompressComplete();
            }

            @Override
            public void onError(String message) {
                if (progressDialog.isAdded()) {
                    progressDialog.dismiss();
                }
                if (message != null && message.contains("password")) {
                    // Show inline error, don't close dialog
                    binding.layoutPassword.setVisibility(View.VISIBLE);
                    binding.layoutPassword.setError(getString(R.string.wrong_password));
                } else {
                    Toast.makeText(requireContext(),
                            getString(R.string.error) + ": " + message,
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
