package com.tools.cnc.ui.archive;

import android.os.Environment;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ArchiveViewModel extends ViewModel {

    private final MutableLiveData<List<FileItem>> fileList = new MutableLiveData<>();
    private final MutableLiveData<String> currentPath = new MutableLiveData<>();
    private final MutableLiveData<Boolean> multiSelectMode = new MutableLiveData<>(false);

    private File currentDir;

    public ArchiveViewModel() {
        navigateTo(Environment.getExternalStorageDirectory());
    }

    public LiveData<List<FileItem>> getFileList() { return fileList; }
    public LiveData<String> getCurrentPath() { return currentPath; }
    public LiveData<Boolean> getMultiSelectMode() { return multiSelectMode; }

    public void navigateTo(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        currentDir = dir;
        currentPath.setValue(dir.getAbsolutePath());
        loadFiles();
    }

    public boolean navigateUp() {
        if (currentDir == null) return false;
        File parent = currentDir.getParentFile();
        if (parent != null && parent.canRead()) {
            navigateTo(parent);
            return true;
        }
        return false;
    }

    public File getCurrentDir() { return currentDir; }

    public void setMultiSelectMode(boolean enabled) {
        multiSelectMode.setValue(enabled);
        if (!enabled) {
            // Clear selections
            List<FileItem> items = fileList.getValue();
            if (items != null) {
                for (FileItem item : items) {
                    item.setSelected(false);
                }
                fileList.setValue(items);
            }
        }
    }

    public List<FileItem> getSelectedItems() {
        List<FileItem> selected = new ArrayList<>();
        List<FileItem> items = fileList.getValue();
        if (items != null) {
            for (FileItem item : items) {
                if (item.isSelected()) {
                    selected.add(item);
                }
            }
        }
        return selected;
    }

    public void refresh() {
        loadFiles();
    }

    private void loadFiles() {
        File[] files = currentDir.listFiles();
        List<FileItem> items = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            for (File file : files) {
                if (!file.getName().startsWith(".")) {
                    items.add(new FileItem(file));
                }
            }
        }
        fileList.setValue(items);
    }
}
