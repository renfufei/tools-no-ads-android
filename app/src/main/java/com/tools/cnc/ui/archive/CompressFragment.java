package com.tools.cnc.ui.archive;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.tools.cnc.R;
import com.tools.cnc.core.archive.ArchiveCallback;
import com.tools.cnc.databinding.FragmentCompressBinding;
import com.tools.cnc.util.StorageHelper;

import java.io.File;
import java.util.List;

public class CompressFragment extends Fragment {

    private FragmentCompressBinding binding;
    private CompressViewModel viewModel;
    private SelectedFileAdapter adapter;
    private ProgressDialogFragment progressDialog;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentCompressBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(this).get(CompressViewModel.class);

        adapter = new SelectedFileAdapter(position -> viewModel.removeEntry(position));
        binding.recyclerSelectedFiles.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerSelectedFiles.setAdapter(adapter);

        binding.btnAddFiles.setOnClickListener(v -> openPicker());
        binding.btnStartCompress.setOnClickListener(v -> showOptionsDialog());

        viewModel.getEntries().observe(getViewLifecycleOwner(), entries -> {
            adapter.setEntries(entries);
            int count = entries.size();
            binding.tvFileCount.setText(getString(R.string.files_added_count, count));
            binding.btnStartCompress.setEnabled(count > 0);
        });

        viewModel.getIsCompressing().observe(getViewLifecycleOwner(), compressing -> {
            binding.btnAddFiles.setEnabled(!compressing);
            binding.btnStartCompress.setEnabled(!compressing && !viewModel.getEntries().getValue().isEmpty());
        });

        // Picked paths arrive from the file picker screen.
        new ViewModelProvider(requireActivity()).get(PickerResultViewModel.class)
                .getPickedPaths()
                .observe(getViewLifecycleOwner(), this::handlePickedPaths);
    }

    private void openPicker() {
        Bundle args = new Bundle();
        args.putString(FilePickerFragment.ARG_MODE, FilePickerViewModel.MODE_COMPRESS);
        Navigation.findNavController(requireView())
                .navigate(R.id.action_compress_to_picker, args);
    }

    @SuppressWarnings("unused")
    private void handlePickedPaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        viewModel.addEntriesFromPaths(requireContext(), paths);
        new ViewModelProvider(requireActivity()).get(PickerResultViewModel.class).consume();
    }

    private void showOptionsDialog() {
        File outputDir = StorageHelper.getCompressOutputDir();
        String defaultName = "archive.zip";

        CompressOptionsDialogFragment dialog =
                CompressOptionsDialogFragment.newInstance(outputDir, defaultName);
        dialog.setOnOptionsConfirmedListener((dir, name, password, level) -> startCompress(dir, name, password, level));
        dialog.show(getChildFragmentManager(), "compress_options");
    }

    private void startCompress(File outputDir, String outputName, String password, int level) {
        progressDialog = ProgressDialogFragment.newInstance(getString(R.string.compressing));
        progressDialog.show(getChildFragmentManager(), "progress");

        viewModel.startCompress(requireContext(), outputDir, outputName, password, level,
                new ArchiveCallback() {
                    @Override
                    public void onProgress(int percentage, String currentFile) {
                        if (progressDialog != null) {
                            progressDialog.updateProgress(percentage, currentFile);
                        }
                    }

                    @Override
                    public void onSuccess(String outputPath) {
                        dismissProgress();
                        Toast.makeText(requireContext(),
                                getString(R.string.success) + "\n" + outputPath,
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onError(String message) {
                        dismissProgress();
                        Toast.makeText(requireContext(),
                                getString(R.string.error) + ": " + message,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            progressDialog.dismissAllowingStateLoss();
            progressDialog = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
