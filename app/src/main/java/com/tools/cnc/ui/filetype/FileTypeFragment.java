package com.tools.cnc.ui.filetype;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.tools.cnc.core.filetype.MagicBytesDetector;
import com.tools.cnc.databinding.FragmentFileTypeBinding;
import com.tools.cnc.util.FileUtils;
import com.tools.cnc.util.StorageHelper;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class FileTypeFragment extends Fragment {

    private FragmentFileTypeBinding binding;
    private FileTypeResultAdapter adapter;

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleSelectedFiles(result.getData());
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentFileTypeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new FileTypeResultAdapter();
        binding.recyclerResults.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerResults.setAdapter(adapter);

        binding.btnSelectFiles.setOnClickListener(v -> openFilePicker());
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(intent);
    }

    private void handleSelectedFiles(Intent data) {
        List<FileTypeResultAdapter.FileTypeResult> results = new ArrayList<>();

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        for (Uri uri : uris) {
            String fileName = StorageHelper.getFileName(requireContext(), uri);
            String extension = FileUtils.getExtension(fileName);

            try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
                MagicBytesDetector.DetectionResult detection = MagicBytesDetector.detectType(is);
                if (detection != null) {
                    boolean matches = extension.equalsIgnoreCase(detection.extension);
                    results.add(new FileTypeResultAdapter.FileTypeResult(
                            fileName, detection.mimeType, detection.description,
                            extension, detection.extension, matches));
                } else {
                    results.add(new FileTypeResultAdapter.FileTypeResult(
                            fileName, "unknown", "未知类型", extension, "", false));
                }
            } catch (Exception e) {
                results.add(new FileTypeResultAdapter.FileTypeResult(
                        fileName, "unknown", "无法读取", extension, "", false));
            }
        }

        adapter.setResults(results);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
