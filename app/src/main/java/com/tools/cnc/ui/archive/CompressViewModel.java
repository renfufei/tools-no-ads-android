package com.tools.cnc.ui.archive;

import android.content.ContentResolver;
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

    /** Chosen output directory; kept here so it survives configuration changes. */
    private String outputDirPath;

    private final ZipHandler zipHandler = new ZipHandler();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public LiveData<List<CompressEntry>> getEntries() {
        return entries;
    }

    public LiveData<Boolean> getIsCompressing() {
        return isCompressing;
    }

    public String getOutputDirPath() {
        return outputDirPath;
    }

    public void setOutputDirPath(String outputDirPath) {
        this.outputDirPath = outputDirPath;
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
            boolean directory = isDirectory(uri);
            current.add(new CompressEntry(uri, name, size, directory));
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
            // Only files we copied into the cache ourselves are ever deleted afterwards -
            // entries picked from the built-in browser are compressed in place.
            List<File> tempFiles = new ArrayList<>();
            try {
                List<File> files = new ArrayList<>();
                for (CompressEntry entry : entryList) {
                    File original = fileOf(entry.getUri());
                    if (original != null && original.exists()) {
                        // Local path: compress straight from the source. Caching would double
                        // the storage requirement and cannot represent a folder at all.
                        files.add(original);
                        continue;
                    }
                    File cached = StorageHelper.copyUriToCache(context, entry.getUri(),
                            entry.getDisplayName());
                    tempFiles.add(cached);
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
                        deleteTempFiles(tempFiles);
                        callback.onSuccess(outputPath);
                    }

                    @Override
                    public void onError(String message) {
                        isCompressing.postValue(false);
                        deleteTempFiles(tempFiles);
                        callback.onError(message);
                    }
                });
            } catch (Exception e) {
                isCompressing.postValue(false);
                deleteTempFiles(tempFiles);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        callback.onError("准备文件失败: " + e.getMessage()));
            }
        });
    }

    /** Returns the local file behind a {@code file://} URI, or {@code null} for other schemes. */
    private static File fileOf(Uri uri) {
        if (uri == null || !ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            return null;
        }
        String path = uri.getPath();
        return path == null ? null : new File(path);
    }

    private static boolean isDirectory(Uri uri) {
        File file = fileOf(uri);
        return file != null && file.isDirectory();
    }

    /**
     * Resolves the entry size. {@code file://} URIs (everything the built-in browser hands
     * over) cannot answer {@link OpenableColumns#SIZE} - querying them used to leave every
     * row showing "0 B".
     */
    private long getFileSize(Context context, Uri uri) {
        File local = fileOf(uri);
        if (local != null) {
            if (local.isDirectory()) {
                return directorySize(local);
            }
            return local.exists() ? local.length() : 0;
        }
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) {
                    return cursor.getLong(idx);
                }
            }
        } catch (Exception ignored) {
            // Unknown provider or unsupported query: leave the size at 0.
        }
        return 0;
    }

    static long directorySize(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        long total = 0;
        for (File child : children) {
            total += child.isDirectory() ? directorySize(child) : child.length();
        }
        return total;
    }

    private static void deleteTempFiles(List<File> files) {
        for (File file : files) {
            if (file != null && file.exists()) {
                file.delete();
            }
        }
        files.clear();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdownNow();
    }
}
