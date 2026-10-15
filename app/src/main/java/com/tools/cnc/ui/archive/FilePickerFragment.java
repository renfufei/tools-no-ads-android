package com.tools.cnc.ui.archive;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

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

import java.util.List;

/**
 * File picker screen, driven by {@link #ARG_MODE}:
 * <ul>
 *   <li>compress — multi-select files and folders, then continue to the
 *       compression screen;</li>
 *   <li>decompress — single-select a ZIP/RAR, then extract it in place.</li>
 * </ul>
 */
public class FilePickerFragment extends Fragment {

    public static final String ARG_MODE = "picker_mode";

    private FragmentFilePickerBinding binding;
    private FilePickerViewModel viewModel;
    private FileAdapter adapter;
    private String mode = FilePickerViewModel.MODE_COMPRESS;

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

        mode = getArguments() == null
                ? FilePickerViewModel.MODE_COMPRESS
                : getArguments().getString(ARG_MODE, FilePickerViewModel.MODE_COMPRESS);
        final boolean compressMode = FilePickerViewModel.MODE_COMPRESS.equals(mode);

        viewModel = new ViewModelProvider(this).get(FilePickerViewModel.class);
        viewModel.setMode(mode);

        binding.tvPickerHint.setText(compressMode
                ? R.string.picker_hint_compress : R.string.picker_hint_decompress);
        binding.tvEmpty.setText(compressMode
                ? R.string.file_picker_empty : R.string.picker_empty_decompress);
        binding.btnConfirm.setText(compressMode
                ? R.string.file_picker_confirm : R.string.picker_start_decompress);
        updateToolbarTitle(compressMode);

        adapter = new FileAdapter();
        // Folder rows get an explicit pick button so "enter" and "select" are distinct.
        adapter.configureSelectionUi(compressMode, true);
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

        viewModel.getCurrentPath().observe(getViewLifecycleOwner(), path ->
                binding.tvCurrentPath.setText(path));

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
    private void updateToolbarTitle(boolean compressMode) {
        if (!(getActivity() instanceof androidx.appcompat.app.AppCompatActivity)) {
            return;
        }
        androidx.appcompat.app.ActionBar actionBar =
                ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(compressMode
                    ? R.string.file_picker_title : R.string.picker_title_decompress);
        }
    }

    private void applySelection(FileItem item, boolean selected) {
        viewModel.applySelection(item, selected);
        updateBottomBar();
    }

    private void updateBottomBar() {
        int count = viewModel.getSelectedCount();
        binding.btnConfirm.setEnabled(count > 0);
        binding.tvSelectedCount.setText(getString(R.string.file_picker_selected_count, count));
    }

    private void confirmSelection() {
        List<String> paths = viewModel.getSelectedPaths();
        if (paths.isEmpty()) {
            return;
        }
        if (FilePickerViewModel.MODE_DECOMPRESS.equals(mode)) {
            showDecompressDialog(paths.get(0));
        } else {
            // Hand the selection over to the compression screen.
            new ViewModelProvider(requireActivity())
                    .get(PickerResultViewModel.class)
                    .setPickedPaths(paths);

            NavController navController = Navigation.findNavController(requireView());
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
    }

    private void showDecompressDialog(String archivePath) {
        DecompressDialogFragment dialog = DecompressDialogFragment.newInstance(archivePath);
        dialog.setOnDecompressListener(() -> {
            viewModel.clearSelection();
            viewModel.refresh();
        });
        dialog.show(getChildFragmentManager(), "decompress");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
