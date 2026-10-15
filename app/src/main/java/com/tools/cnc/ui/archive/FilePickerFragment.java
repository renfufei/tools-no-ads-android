package com.tools.cnc.ui.archive;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.tools.cnc.R;
import com.tools.cnc.databinding.FragmentFilePickerBinding;

import java.io.File;
import java.util.List;

/**
 * File picker screen, driven by {@link #ARG_MODE}:
 * <ul>
 *   <li>compress — multi-select files and folders, then continue to the
 *       compression screen;</li>
 *   <li>decompress — single-select a ZIP/RAR, then continue to the decompression
 *       screen (folders are only entered, never selected);</li>
 *   <li>pick_dir — browse folders only and confirm the current one (used for the
 *       compression/decompression output directory).</li>
 * </ul>
 */
public class FilePickerFragment extends Fragment {

    public static final String ARG_MODE = "picker_mode";
    /** Optional initial folder, used by the directory picker. */
    public static final String ARG_START_DIR = "picker_start_dir";

    private FragmentFilePickerBinding binding;
    private FilePickerViewModel viewModel;
    private FileAdapter adapter;
    private String mode = FilePickerViewModel.MODE_COMPRESS;
    private boolean compressMode = true;
    private boolean dirMode = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentFilePickerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        mode = args == null
                ? FilePickerViewModel.MODE_COMPRESS
                : args.getString(ARG_MODE, FilePickerViewModel.MODE_COMPRESS);
        compressMode = FilePickerViewModel.MODE_COMPRESS.equals(mode);
        dirMode = FilePickerViewModel.MODE_PICK_DIR.equals(mode);

        viewModel = new ViewModelProvider(this).get(FilePickerViewModel.class);
        viewModel.setMode(mode);

        // Open on the caller's folder when it asked for one.
        String startDir = args == null ? null : args.getString(ARG_START_DIR);
        if (startDir != null) {
            viewModel.navigateTo(new File(startDir));
        }

        if (dirMode) {
            binding.tvPickerHint.setText(R.string.picker_hint_dir);
            binding.tvEmpty.setText(R.string.picker_empty_dir);
            binding.btnConfirm.setText(R.string.picker_use_current_dir);
        } else if (compressMode) {
            binding.tvPickerHint.setText(R.string.picker_hint_compress);
            binding.tvEmpty.setText(R.string.file_picker_empty);
            binding.btnConfirm.setText(R.string.file_picker_confirm);
        } else {
            binding.tvPickerHint.setText(R.string.picker_hint_decompress);
            binding.tvEmpty.setText(R.string.picker_empty_decompress);
            binding.btnConfirm.setText(R.string.file_picker_confirm);
        }
        updateToolbarTitle();

        adapter = new FileAdapter();
        // Folder rows get an explicit pick button so "enter" and "select" are distinct.
        // Decompress mode never selects folders: it only marks the chosen archive with
        // a check mark (multi-select compression keeps the checkbox instead).
        adapter.configureSelectionUi(compressMode, compressMode, !compressMode && !dirMode);
        binding.recyclerFiles.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerFiles.setAdapter(adapter);

        adapter.setOnFileClickListener(item -> viewModel.navigateTo(item.getFile()));
        adapter.setOnFolderPickListener(item ->
                applySelection(item, !item.isSelected()));
        adapter.setOnSelectionToggleListener(this::applySelection);

        viewModel.getFileList().observe(getViewLifecycleOwner(), items -> {
            adapter.submitList(items);
            binding.tvEmpty.setVisibility(
                    (items == null || items.isEmpty()) ? View.VISIBLE : View.GONE);
            updateBottomBar();
        });

        viewModel.getCurrentPath().observe(getViewLifecycleOwner(), path -> {
            binding.tvCurrentPath.setText(path);
            updateBottomBar();
        });

        binding.btnConfirm.setOnClickListener(v -> confirmSelection());

        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(), new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (!viewModel.navigateUp()) {
                            setEnabled(false);
                            requireActivity().getOnBackPressedDispatcher().onBackPressed();
                        }
                    }
                });

        updateBottomBar();
    }

    /** Toolbar title reflects what the user is picking. */
    private void updateToolbarTitle() {
        if (!(getActivity() instanceof androidx.appcompat.app.AppCompatActivity)) {
            return;
        }
        androidx.appcompat.app.ActionBar actionBar =
                ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar();
        if (actionBar == null) {
            return;
        }
        if (dirMode) {
            actionBar.setTitle(R.string.picker_title_dir);
        } else if (compressMode) {
            actionBar.setTitle(R.string.file_picker_title);
        } else {
            actionBar.setTitle(R.string.picker_title_decompress);
        }
    }

    private void applySelection(FileItem item, boolean selected) {
        viewModel.applySelection(item, selected);
        updateBottomBar();
    }

    private void updateBottomBar() {
        if (dirMode) {
            // Nothing to select: the folder currently being browsed is the answer.
            File dir = viewModel.getCurrentDir();
            binding.btnConfirm.setEnabled(dir != null);
            binding.tvSelectedCount.setText(dir == null ? ""
                    : getString(R.string.picker_current_dir, dir.getAbsolutePath()));
            return;
        }
        int count = viewModel.getSelectedCount();
        binding.btnConfirm.setEnabled(count > 0);
        binding.tvSelectedCount.setText(getString(R.string.file_picker_selected_count, count));
    }

    private void confirmSelection() {
        if (dirMode) {
            File dir = viewModel.getCurrentDir();
            if (dir == null) {
                return;
            }
            new ViewModelProvider(requireActivity())
                    .get(PickerResultViewModel.class)
                    .setPickedDirectory(dir.getAbsolutePath());
            Navigation.findNavController(requireView()).popBackStack();
            return;
        }

        List<String> paths = viewModel.getSelectedPaths();
        if (paths.isEmpty()) {
            return;
        }

        NavController navController = Navigation.findNavController(requireView());
        if (FilePickerViewModel.MODE_DECOMPRESS.equals(mode)) {
            // Continue to the decompression screen; the picker is popped by the action
            // so backing out of extraction returns straight to the archive menu.
            navController.navigate(R.id.action_picker_to_decompress,
                    DecompressFragment.argsFor(paths.get(0)));
            return;
        }

        // Hand the selection over to the compression screen.
        new ViewModelProvider(requireActivity())
                .get(PickerResultViewModel.class)
                .setPickedPaths(paths);

        NavBackStackEntry previous = navController.getPreviousBackStackEntry();
        if (previous != null
                && previous.getDestination().getId() == R.id.compressFragment) {
            // Adding more files to an existing selection: return to that screen
            // instead of stacking another compression screen on top of it.
            navController.popBackStack();
        } else {
            navController.navigate(R.id.action_picker_to_compress);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
