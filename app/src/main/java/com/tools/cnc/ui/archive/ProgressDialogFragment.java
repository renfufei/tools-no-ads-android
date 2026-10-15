package com.tools.cnc.ui.archive;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.tools.cnc.R;
import com.tools.cnc.databinding.DialogProgressBinding;

public class ProgressDialogFragment extends DialogFragment {

    private static final String ARG_TITLE = "title";
    private DialogProgressBinding binding;

    public static ProgressDialogFragment newInstance(String title) {
        ProgressDialogFragment fragment = new ProgressDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        binding = DialogProgressBinding.inflate(getLayoutInflater());
        setCancelable(false);

        String title = getArguments() != null ? getArguments().getString(ARG_TITLE) : "";
        binding.tvStatus.setText(title);

        return new AlertDialog.Builder(requireContext())
                .setView(binding.getRoot())
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    // TODO: Cancel operation
                    dismiss();
                })
                .create();
    }

    public void updateProgress(int percentage, String currentFile) {
        if (binding == null) return;
        requireActivity().runOnUiThread(() -> {
            if (binding != null) {
                binding.progressBar.setProgress(percentage);
                if (currentFile != null) {
                    binding.tvCurrentFile.setText(currentFile);
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
