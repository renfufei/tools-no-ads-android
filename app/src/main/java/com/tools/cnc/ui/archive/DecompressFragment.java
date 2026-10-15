package com.tools.cnc.ui.archive;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;

import com.tools.cnc.R;
import com.tools.cnc.core.archive.ArchiveCallback;
import com.tools.cnc.core.archive.ArchiveFormat;
import com.tools.cnc.core.archive.ArchiveManager;
import com.tools.cnc.databinding.FragmentDecompressBinding;
import com.tools.cnc.util.FileUtils;
import com.tools.cnc.util.StorageHelper;

import java.io.File;

/**
 * Decompression screen. Mirrors the compression flow: the archive to extract, the
 * destination folder (changeable) and the output folder name are all set here, so the
 * previous dialog - which could not show any of that - is gone.
 */
public class DecompressFragment extends Fragment {

    public static final String ARG_ARCHIVE_PATH = "archive_path";

    private FragmentDecompressBinding binding;
    private DecompressViewModel viewModel;
    private ProgressDialogFragment progressDialog;

    private File archiveFile;
    private File targetDir;
    private boolean unsupportedRar5;

    private final TextWatcher folderNameWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            if (binding == null) {
                return;
            }
            binding.layoutFolderName.setError(null);
            updateFinalPath();
        }
    };

    /** Builds the navigation arguments for this screen. */
    public static Bundle argsFor(String archivePath) {
        Bundle args = new Bundle();
        args.putString(ARG_ARCHIVE_PATH, archivePath);
        return args;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentDecompressBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(this).get(DecompressViewModel.class);

        Bundle args = getArguments();
        String path = args == null ? null : args.getString(ARG_ARCHIVE_PATH);
        archiveFile = path == null ? null : new File(path);

        if (archiveFile == null || !archiveFile.exists()) {
            binding.tvArchiveName.setText(path == null ? "" : path);
            binding.btnStartDecompress.setEnabled(false);
            ErrorDialogFragment.showError(getChildFragmentManager(), getString(R.string.error),
                    "压缩包不存在或已被移动：\n" + (path == null ? "（无路径）" : path));
            return;
        }

        binding.tvArchiveName.setText(archiveFile.getName());
        binding.tvArchiveInfo.setText(getString(R.string.decompress_info,
                FileUtils.formatFileSize(archiveFile.length()),
                ArchiveFormat.describe(archiveFile),
                FileUtils.formatDate(archiveFile.lastModified())));

        String savedDir = viewModel.getTargetDirPath();
        targetDir = savedDir != null ? new File(savedDir) : defaultTargetDir();
        viewModel.setTargetDirPath(targetDir.getAbsolutePath());
        binding.tvTargetDir.setText(targetDir.getAbsolutePath());

        // A restored view state overrides this with whatever the user typed.
        String savedName = viewModel.getFolderName();
        binding.etFolderName.setText(savedName != null ? savedName : defaultFolderName());
        binding.etFolderName.addTextChangedListener(folderNameWatcher);

        binding.btnChangeTargetDir.setOnClickListener(v -> openDirectoryPicker());
        binding.btnStartDecompress.setOnClickListener(v -> onStartClicked());

        viewModel.getArchiveInfo().observe(getViewLifecycleOwner(), this::applyArchiveInfo);
        viewModel.getIsDecompressing().observe(getViewLifecycleOwner(), this::applyBusy);
        viewModel.loadArchiveInfo(archiveFile);

        new ViewModelProvider(requireActivity()).get(PickerResultViewModel.class)
                .getPickedDirectory().observe(getViewLifecycleOwner(), this::handlePickedDirectory);

        updateFinalPath();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // -------------------------------------------------------------------------
    // Destination
    // -------------------------------------------------------------------------

    /** Output goes next to the archive unless the user picked somewhere else. */
    private File defaultTargetDir() {
        File parent = archiveFile.getParentFile();
        return parent != null ? parent : StorageHelper.getCompressOutputDir();
    }

    /** Default output folder name: the archive name without its extension. */
    private String defaultFolderName() {
        return FileUtils.getNameWithoutExtension(archiveFile.getName());
    }

    private void openDirectoryPicker() {
        Bundle args = new Bundle();
        args.putString(FilePickerFragment.ARG_MODE, FilePickerViewModel.MODE_PICK_DIR);
        args.putString(FilePickerFragment.ARG_START_DIR,
                targetDir != null ? targetDir.getAbsolutePath() : null);
        Navigation.findNavController(requireView())
                .navigate(R.id.action_decompress_to_picker, args);
    }

    private void handlePickedDirectory(String path) {
        if (path == null || binding == null) {
            return;
        }
        targetDir = new File(path);
        viewModel.setTargetDirPath(path);
        binding.tvTargetDir.setText(path);
        updateFinalPath();
        new ViewModelProvider(requireActivity()).get(PickerResultViewModel.class)
                .consumeDirectory();
    }

    private void updateFinalPath() {
        if (binding == null) {
            return;
        }
        if (targetDir == null) {
            binding.tvFinalPath.setText("");
            return;
        }
        String name = binding.etFolderName.getText() == null
                ? "" : binding.etFolderName.getText().toString().trim();
        binding.tvFinalPath.setText(getString(R.string.decompress_final_path,
                new File(targetDir, name).getAbsolutePath()));
    }

    // -------------------------------------------------------------------------
    // Archive info
    // -------------------------------------------------------------------------

    private void applyArchiveInfo(DecompressViewModel.ArchiveInfo info) {
        if (binding == null || info == null) {
            return;
        }
        unsupportedRar5 = info.unsupportedRar5;
        binding.tvFormatWarning.setVisibility(unsupportedRar5 ? View.VISIBLE : View.GONE);
        binding.layoutPasswordWrapper.setVisibility(info.encrypted ? View.VISIBLE : View.GONE);
        binding.btnStartDecompress.setEnabled(!unsupportedRar5
                && !Boolean.TRUE.equals(viewModel.getIsDecompressing().getValue()));
    }

    private void applyBusy(Boolean busy) {
        if (binding == null) {
            return;
        }
        boolean isBusy = Boolean.TRUE.equals(busy);
        binding.btnChangeTargetDir.setEnabled(!isBusy);
        binding.etFolderName.setEnabled(!isBusy);
        binding.etPassword.setEnabled(!isBusy);
        binding.btnStartDecompress.setEnabled(!isBusy && !unsupportedRar5);
    }

    // -------------------------------------------------------------------------
    // Extraction
    // -------------------------------------------------------------------------

    private void onStartClicked() {
        final String folderName = validatedFolderName();
        if (folderName == null) {
            return;
        }

        File dir = targetDir != null ? targetDir : defaultTargetDir();
        if (!dir.exists() && !dir.mkdirs()) {
            ErrorDialogFragment.showError(getChildFragmentManager(), getString(R.string.error),
                    getString(R.string.compress_dir_unusable, dir.getAbsolutePath()));
            return;
        }

        String password = null;
        if (binding.layoutPasswordWrapper.getVisibility() == View.VISIBLE) {
            CharSequence typed = binding.etPassword.getText();
            password = typed == null ? "" : typed.toString();
            if (password.isEmpty()) {
                binding.layoutPassword.setError(getString(R.string.decompress_encrypted_hint));
                return;
            }
            binding.layoutPassword.setError(null);
        }

        viewModel.setTargetDirPath(dir.getAbsolutePath());
        viewModel.setFolderName(folderName);

        final File outputDir = new File(dir, folderName);

        progressDialog = ProgressDialogFragment.newInstance(getString(R.string.decompressing));
        progressDialog.show(getChildFragmentManager(), "progress");
        viewModel.setDecompressing(true);

        new ArchiveManager().decompress(archiveFile, outputDir, password, new ArchiveCallback() {
            @Override
            public void onProgress(int percentage, String currentFile) {
                if (progressDialog != null) {
                    progressDialog.updateProgress(percentage, currentFile);
                }
            }

            @Override
            public void onSuccess(String outputPath) {
                viewModel.setDecompressing(false);
                dismissProgress();
                Toast.makeText(requireContext(),
                        getString(R.string.decompress_done, outputPath),
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String message) {
                viewModel.setDecompressing(false);
                dismissProgress();
                // Full detail goes into a scrollable dialog instead of a toast.
                ErrorDialogFragment.showError(getChildFragmentManager(),
                        getString(R.string.error), message);
            }
        });
    }

    /** Trimmed folder name, or {@code null} after reporting the problem inline. */
    @Nullable
    private String validatedFolderName() {
        if (binding.etFolderName.getText() == null) {
            binding.layoutFolderName.setError(getString(R.string.decompress_folder_empty));
            return null;
        }
        String name = binding.etFolderName.getText().toString().trim();
        if (name.isEmpty()) {
            binding.layoutFolderName.setError(getString(R.string.decompress_folder_empty));
            return null;
        }
        if (name.contains("/") || name.contains("\\")) {
            binding.layoutFolderName.setError(getString(R.string.decompress_folder_invalid));
            return null;
        }
        binding.layoutFolderName.setError(null);
        return name;
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            progressDialog.dismissAllowingStateLoss();
            progressDialog = null;
        }
    }
}
