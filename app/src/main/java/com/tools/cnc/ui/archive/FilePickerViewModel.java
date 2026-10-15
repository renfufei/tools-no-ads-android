package com.tools.cnc.ui.archive;

import android.os.Environment;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * File browser backing the picker screen. Works in two modes:
 * <ul>
 *   <li>{@link #MODE_COMPRESS} — lists every file and folder, multi-select.</li>
 *   <li>{@link #MODE_DECOMPRESS} — lists folders plus ZIP/RAR archives only,
 *       single-select.</li>
 * </ul>
 */
public class FilePickerViewModel extends ViewModel {

    public static final String MODE_COMPRESS = "compress";
    public static final String MODE_DECOMPRESS = "decompress";

    private final MutableLiveData<List<FileItem>> fileList = new MutableLiveData<>();
    private final MutableLiveData<String> currentPath = new MutableLiveData<>();

    private String mode = MODE_COMPRESS;
    private File currentDir;

    public FilePickerViewModel() {
        navigateTo(Environment.getExternalStorageDirectory());
    }

    public LiveData<List<FileItem>> getFileList() {
        return fileList;
    }

    public LiveData<String> getCurrentPath() {
        return currentPath;
    }

    public void setMode(String mode) {
        if (mode == null) {
            return;
        }
        this.mode = mode;
        loadFiles();
    }

    public boolean isCompressMode() {
        return MODE_COMPRESS.equals(mode);
    }

    public void navigateTo(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        currentDir = dir;
        currentPath.setValue(dir.getAbsolutePath());
        loadFiles();
    }

    public boolean navigateUp() {
        if (currentDir == null) {
            return false;
        }
        File parent = currentDir.getParentFile();
        if (parent != null && parent.canRead()) {
            navigateTo(parent);
            return true;
        }
        return false;
    }

    public File getCurrentDir() {
        return currentDir;
    }

    /**
     * Applies a selection change. In decompress mode selection is exclusive
     * (one archive at a time).
     */
    public void applySelection(FileItem item, boolean selected) {
        if (item == null) {
            return;
        }
        List<FileItem> items = fileList.getValue();
        if (items == null) {
            return;
        }
        if (!isCompressMode()) {
            // Exclusive selection: one archive at a time.
            for (FileItem other : items) {
                if (other != item) {
                    other.setSelected(false);
                }
            }
        }
        item.setSelected(selected);
        notifyListChanged(items);
    }

    public void clearSelection() {
        List<FileItem> items = fileList.getValue();
        if (items != null) {
            for (FileItem item : items) {
                item.setSelected(false);
            }
            notifyListChanged(items);
        }
    }

    /**
     * Re-publishes a fresh list snapshot so the adapter's DiffUtil picks up the
     * selection change (avoids notifyDataSetChanged on a ListAdapter).
     */
    private void notifyListChanged(List<FileItem> items) {
        fileList.setValue(new ArrayList<>(items));
    }

    public int getSelectedCount() {
        int count = 0;
        List<FileItem> items = fileList.getValue();
        if (items != null) {
            for (FileItem item : items) {
                if (item.isSelected()) {
                    count++;
                }
            }
        }
        return count;
    }

    public List<String> getSelectedPaths() {
        List<String> paths = new ArrayList<>();
        List<FileItem> items = fileList.getValue();
        if (items != null) {
            for (FileItem item : items) {
                if (item.isSelected()) {
                    paths.add(item.getFile().getAbsolutePath());
                }
            }
        }
        return paths;
    }

    public void refresh() {
        loadFiles();
    }

    private void loadFiles() {
        if (currentDir == null) {
            return;
        }
        File[] files = currentDir.listFiles();
        List<FileItem> items = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            for (File file : files) {
                if (file.getName().startsWith(".")) {
                    continue;
                }
                FileItem item = new FileItem(file);
                // Decompress mode only shows folders and supported archives.
                if (!isCompressMode() && item.getType() != FileItem.FileType.FOLDER
                        && !item.isArchive()) {
                    continue;
                }
                items.add(item);
            }
        }
        fileList.setValue(items);
    }
}
