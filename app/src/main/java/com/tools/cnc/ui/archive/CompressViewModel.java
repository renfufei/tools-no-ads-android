package com.tools.cnc.ui.archive;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.tools.cnc.core.archive.ArchiveCallback;
import com.tools.cnc.core.archive.ZipHandler;
import com.tools.cnc.util.StorageHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CompressViewModel extends ViewModel {

    private final MutableLiveData<List<CompressEntry>> entries = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> isCompressing = new MutableLiveData<>(false);

    private final ZipHandler zipHandler = new ZipHandler();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<File> cachedFiles = new ArrayList<>();

    public LiveData<List<CompressEntry>> getEntries() {
        return entries;
    }

    public LiveData<Boolean> getIsCompressing() {
        return isCompressing;
    }

    public void addEntriesFromPaths(Context context, List<String> paths) {
        List<Uri> uris = new ArrayList<>();
        for (String path : paths) {
            File file = new File(path);
            if (file.exists()) {
                uris.add(Uri.fromFile(file));
            }
        }
        if (!uris.isEmpty()) {
            addEntries(context, uris);
        }
    }

    public void addEntries(Context context, List<Uri> uris) {
        List<CompressEntry> current = new ArrayList<>(entries.getValue());
        Set<String> existingUris = new HashSet<>();
        for (CompressEntry entry : current) {
            existingUris.add(entry.getUri().toString());
        }

        for (Uri uri : uris) {
            if (existingUris.contains(uri.toString())) {
                continue;
            }
            String name = StorageHelper.getFileName(context, uri);
            long size = getFileSize(context, uri);
            current.add(new CompressEntry(uri, name, size));
            existingUris.add(uri.toString());
        }

        entries.setValue(current);
    }

    public void removeEntry(int position) {
        List<CompressEntry> current = new ArrayList<>(entries.getValue());
        if (position >= 0 && position < current.size()) {
            current.remove(position);
            entries.setValue(current);
        }
    }

    public void startCompress(Context context, File outputDir, String outputName,
                              String password, int level, ArchiveCallback callback) {
        List<CompressEntry> entryList = entries.getValue();
        if (entryList == null || entryList.isEmpty()) {
            callback.onError("文件列表为空");
            return;
        }

        isCompressing.setValue(true);

        executor.execute(() -> {
            try {
                List<File> files = new ArrayList<>();
                for (CompressEntry entry : entryList) {
                    File cached = StorageHelper.copyUriToCache(context, entry.getUri(), entry.getDisplayName());
                    entry.setCachedFile(cached);
                    cachedFiles.add(cached);
                    files.add(cached);
                }

                if (!outputDir.exists()) {
                    outputDir.mkdirs();
                }
                File outputZip = new File(outputDir, outputName);

                zipHandler.compress(files, outputZip, password, level, new ArchiveCallback() {
                    @Override
                    public void onProgress(int percentage, String currentFile) {
                        callback.onProgress(percentage, currentFile);
                    }

                    @Override
                    public void onSuccess(String outputPath) {
                        isCompressing.postValue(false);
                        cleanupCache();
                        callback.onSuccess(outputPath);
                    }

                    @Override
                    public void onError(String message) {
                        isCompressing.postValue(false);
                        cleanupCache();
                        callback.onError(message);
                    }
                });
            } catch (Exception e) {
                isCompressing.postValue(false);
                cleanupCache();
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        callback.onError("准备文件失败: " + e.getMessage()));
            }
        });
    }

    private long getFileSize(Context context, Uri uri) {
        long size = 0;
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) {
                    size = cursor.getLong(idx);
                }
            }
        }
        return size;
    }

    private void cleanupCache() {
        for (File file : cachedFiles) {
            if (file != null && file.exists()) {
                file.delete();
            }
        }
        cachedFiles.clear();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        cleanupCache();
        executor.shutdownNow();
    }
}
