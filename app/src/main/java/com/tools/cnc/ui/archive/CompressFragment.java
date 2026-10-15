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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Compression screen. Everything the archive needs is configured here - destination,
 * file name and the optional password / compression level - so no dialog is involved.
 * The optional part sits in a collapsed section that the user opens only when needed.
 *
 * <p>Failures are reported through {@link ErrorDialogFragment}: a toast cannot carry
 * the detail that makes an archive error actionable.
 */
public class CompressFragment extends Fragment {

    private FragmentCompressBinding binding;
    private CompressViewModel viewModel;
    private SelectedFileAdapter adapter;
    private ProgressDialogFragment progressDialog;

    private File outputDir;

    /** The password / level section is collapsed by default. */
    private boolean optionsExpanded;

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

        String savedDir = viewModel.getOutputDirPath();
        outputDir = savedDir != null ? new File(savedDir) : StorageHelper.getCompressOutputDir();
        viewModel.setOutputDirPath(outputDir.getAbsolutePath());
        binding.tvOutputDir.setText(outputDir.getAbsolutePath());
        // A restored (rotated) view state overwrites this with the name the user edited.
        binding.etOutputName.setText(defaultOutputName());

        adapter = new SelectedFileAdapter(position -> viewModel.removeEntry(position));
        binding.recyclerSelectedFiles.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerSelectedFiles.setAdapter(adapter);

        binding.btnChangeOutputDir.setOnClickListener(v -> openDirectoryPicker());
        binding.btnAddFiles.setOnClickListener(v -> openFilePicker());
        binding.btnStartCompress.setOnClickListener(v -> onStartClicked());
        binding.rowOptionsToggle.setOnClickListener(v -> setOptionsExpanded(!optionsExpanded));

        binding.switchPassword.setOnCheckedChangeListener((button, checked) -> {
            binding.layoutPassword.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked) {
                binding.etPassword.requestFocus();
            } else {
                binding.layoutPassword.setError(null);
            }
            updateOptionsSummary();
        });

        binding.sliderLevel.setLabelFormatter(value ->
                getString(R.string.compress_level_value, (int) value));
        binding.sliderLevel.addOnChangeListener((slider, value, fromUser) ->
                updateOptionsSummary());

        updateOptionsSummary();

        viewModel.getEntries().observe(getViewLifecycleOwner(), entries -> {
            adapter.setEntries(entries);
            int count = entries.size();
            binding.tvFileCount.setText(getString(R.string.files_added_count, count));
            binding.btnStartCompress.setEnabled(count > 0);
        });

        viewModel.getIsCompressing().observe(getViewLifecycleOwner(), compressing -> {
            binding.btnAddFiles.setEnabled(!compressing);
            binding.btnChangeOutputDir.setEnabled(!compressing);
            binding.etOutputName.setEnabled(!compressing);
            binding.switchPassword.setEnabled(!compressing);
            binding.etPassword.setEnabled(!compressing);
            binding.sliderLevel.setEnabled(!compressing);
            binding.btnStartCompress.setEnabled(!compressing
                    && !viewModel.getEntries().getValue().isEmpty());
        });

        // Results come back through the activity-scoped channel.
        PickerResultViewModel picker =
                new ViewModelProvider(requireActivity()).get(PickerResultViewModel.class);
        picker.getPickedPaths().observe(getViewLifecycleOwner(), this::handlePickedPaths);
        picker.getPickedDirectory().observe(getViewLifecycleOwner(), this::handlePickedDirectory);
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        // The switch and the slider restore their own values only after onViewCreated ran,
        // so the dependent labels and the password field visibility are refreshed here.
        if (binding == null) {
            return;
        }
        binding.layoutPassword.setVisibility(
                binding.switchPassword.isChecked() ? View.VISIBLE : View.GONE);
        updateOptionsSummary();
    }

    // -------------------------------------------------------------------------
    // Optional settings section
    // -------------------------------------------------------------------------

    private void setOptionsExpanded(boolean expanded) {
        if (binding == null) {
            return;
        }
        optionsExpanded = expanded;
        binding.layoutCompressOptions.setVisibility(expanded ? View.VISIBLE : View.GONE);
        binding.ivOptionsArrow.setRotation(expanded ? 180f : 0f);
    }

    /** Keeps the collapsed header row readable: "无密码 · 等级 5" / "已加密 · 等级 5". */
    private void updateOptionsSummary() {
        if (binding == null) {
            return;
        }
        int level = (int) binding.sliderLevel.getValue();
        binding.tvLevelValue.setText(getString(R.string.compress_level_value, level));
        binding.tvOptionsSummary.setText(getString(
                binding.switchPassword.isChecked()
                        ? R.string.compress_options_summary_locked
                        : R.string.compress_options_summary_plain,
                level));
    }

    // -------------------------------------------------------------------------
    // Picking
    // -------------------------------------------------------------------------

    /** Default archive name: 压缩包-yyyyMMddHHmmss.zip */
    private String defaultOutputName() {
        String stamp = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(new Date());
        return getString(R.string.compress_default_name, stamp);
    }

    private void openFilePicker() {
        Bundle args = new Bundle();
        args.putString(FilePickerFragment.ARG_MODE, FilePickerViewModel.MODE_COMPRESS);
        Navigation.findNavController(requireView())
                .navigate(R.id.action_compress_to_picker, args);
    }

    private void openDirectoryPicker() {
        Bundle args = new Bundle();
        args.putString(FilePickerFragment.ARG_MODE, FilePickerViewModel.MODE_PICK_DIR);
        args.putString(FilePickerFragment.ARG_START_DIR, outputDir.getAbsolutePath());
        Navigation.findNavController(requireView())
                .navigate(R.id.action_compress_to_picker, args);
    }

    private void handlePickedPaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        viewModel.addEntriesFromPaths(requireContext(), paths);
        new ViewModelProvider(requireActivity()).get(PickerResultViewModel.class).consume();
    }

    private void handlePickedDirectory(String path) {
        if (path == null) {
            return;
        }
        outputDir = new File(path);
        viewModel.setOutputDirPath(path);
        binding.tvOutputDir.setText(outputDir.getAbsolutePath());
        new ViewModelProvider(requireActivity()).get(PickerResultViewModel.class)
                .consumeDirectory();
    }

    // -------------------------------------------------------------------------
    // Compression
    // -------------------------------------------------------------------------

    private void onStartClicked() {
        if (outputDir == null) {
            outputDir = StorageHelper.getCompressOutputDir();
            viewModel.setOutputDirPath(outputDir.getAbsolutePath());
            binding.tvOutputDir.setText(outputDir.getAbsolutePath());
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            ErrorDialogFragment.showError(getChildFragmentManager(), getString(R.string.error),
                    getString(R.string.compress_dir_unusable, outputDir.getAbsolutePath()));
            return;
        }

        String name = normalizedOutputName();
        if (name.isEmpty()) {
            binding.layoutOutputName.setError(getString(R.string.compress_name_empty));
            return;
        }
        binding.layoutOutputName.setError(null);
        binding.etOutputName.setText(name);

        String password = null;
        if (binding.switchPassword.isChecked()) {
            CharSequence typed = binding.etPassword.getText();
            password = typed == null ? "" : typed.toString();
            if (password.isEmpty()) {
                // The error is only visible if the section is open.
                setOptionsExpanded(true);
                binding.layoutPassword.setError(getString(R.string.compress_password_empty));
                return;
            }
        }
        binding.layoutPassword.setError(null);

        int level = (int) binding.sliderLevel.getValue();
        startCompress(outputDir, name, password, level);
    }

    /** Trimmed file name, without path separators and always ending in {@code .zip}. */
    private String normalizedOutputName() {
        if (binding.etOutputName.getText() == null) {
            return "";
        }
        String name = binding.etOutputName.getText().toString().trim();
        if (name.isEmpty()) {
            return "";
        }
        name = name.replace('/', '_').replace('\\', '_');
        if (!name.toLowerCase(Locale.US).endsWith(".zip")) {
            name = name + ".zip";
        }
        return name;
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
                        ErrorDialogFragment.showError(getChildFragmentManager(),
                                getString(R.string.error), message);
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
