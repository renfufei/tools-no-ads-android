package com.tools.cnc.ui.archive;

import android.app.Dialog;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.tools.cnc.R;
import com.tools.cnc.core.archive.ArchiveCallback;
import com.tools.cnc.core.archive.ZipHandler;
import com.tools.cnc.databinding.DialogCompressBinding;
import com.tools.cnc.util.StorageHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CompressDialogFragment extends DialogFragment {

    private DialogCompressBinding binding;
    private List<FileItem> selectedItems;
    private OnCompressListener listener;

    public interface OnCompressListener {
        void onCompressComplete();
    }

    public static CompressDialogFragment newInstance(List<FileItem> items) {
        CompressDialogFragment fragment = new CompressDialogFragment();
        fragment.selectedItems = items;
        return fragment;
    }

    public void setOnCompressListener(OnCompressListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        binding = DialogCompressBinding.inflate(getLayoutInflater());

        binding.switchPassword.setOnCheckedChangeListener((buttonView, isChecked) -> {
            binding.layoutPassword.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        // Default output name
        if (selectedItems != null && !selectedItems.isEmpty()) {
            String defaultName = selectedItems.size() == 1
                    ? com.tools.cnc.util.FileUtils.getNameWithoutExtension(selectedItems.get(0).getName())
                    : "archive";
            binding.etOutputName.setText(defaultName);
        }

        return new AlertDialog.Builder(requireContext())
                .setTitle(R.string.compress)
                .setView(binding.getRoot())
                .setPositiveButton(R.string.confirm, (dialog, which) -> startCompress())
                .setNegativeButton(R.string.cancel, null)
                .create();
    }

    private void startCompress() {
        if (selectedItems == null || selectedItems.isEmpty()) return;

        String outputName = binding.etOutputName.getText().toString().trim();
        if (outputName.isEmpty()) outputName = "archive";

        String password = null;
        if (binding.switchPassword.isChecked()) {
            password = binding.etPassword.getText().toString();
            if (password.isEmpty()) password = null;
        }

        int level = (int) binding.sliderLevel.getValue();

        List<File> files = new ArrayList<>();
        for (FileItem item : selectedItems) {
            files.add(item.getFile());
        }

        File outputDir = selectedItems.get(0).getFile().getParentFile();
        File outputFile = new File(outputDir, outputName + ".zip");

        ProgressDialogFragment progressDialog = ProgressDialogFragment.newInstance(
                getString(R.string.compressing));
        progressDialog.show(getParentFragmentManager(), "progress");

        new ZipHandler().compress(files, outputFile, password, level, new ArchiveCallback() {
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
                if (listener != null) listener.onCompressComplete();
            }

            @Override
            public void onError(String message) {
                if (progressDialog.isAdded()) {
                    progressDialog.dismiss();
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
