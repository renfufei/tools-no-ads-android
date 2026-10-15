package com.tools.cnc.ui.archive;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.tools.cnc.core.archive.ArchiveFormat;
import com.tools.cnc.core.archive.ArchiveManager;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * State for the decompression screen: what the archive turns out to be, plus the
 * target folder the user picked. Kept in a ViewModel so a rotation neither re-probes
 * the archive nor loses the chosen destination.
 */
public class DecompressViewModel extends ViewModel {

    /** Everything learned about the archive before extraction starts. */
    public static class ArchiveInfo {
        public final String formatLabel;
        public final boolean encrypted;
        public final boolean unsupportedRar5;

        ArchiveInfo(String formatLabel, boolean encrypted, boolean unsupportedRar5) {
            this.formatLabel = formatLabel;
            this.encrypted = encrypted;
            this.unsupportedRar5 = unsupportedRar5;
        }
    }

    private final MutableLiveData<ArchiveInfo> archiveInfo = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isDecompressing = new MutableLiveData<>(false);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private String targetDirPath;
    private String folderName;
    private boolean infoRequested;

    public LiveData<ArchiveInfo> getArchiveInfo() {
        return archiveInfo;
    }

    public LiveData<Boolean> getIsDecompressing() {
        return isDecompressing;
    }

    public String getTargetDirPath() {
        return targetDirPath;
    }

    public void setTargetDirPath(String targetDirPath) {
        this.targetDirPath = targetDirPath;
    }

    public String getFolderName() {
        return folderName;
    }

    public void setFolderName(String folderName) {
        this.folderName = folderName;
    }

    public void setDecompressing(boolean value) {
        isDecompressing.setValue(value);
    }

    /** Probes the container format and the encryption flag (once, off the main thread). */
    public void loadArchiveInfo(File archive) {
        if (archive == null || infoRequested) {
            return;
        }
        infoRequested = true;
        executor.execute(() -> {
            boolean unsupportedRar5 = ArchiveFormat.isUnsupportedRar5(archive);
            boolean encrypted = false;
            if (!unsupportedRar5) {
                try {
                    encrypted = new ArchiveManager().isEncrypted(archive);
                } catch (Throwable ignored) {
                    // Unknown encryption state: the extraction call will report it.
                }
            }
            archiveInfo.postValue(new ArchiveInfo(
                    ArchiveFormat.describe(archive), encrypted, unsupportedRar5));
        });
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdownNow();
    }
}
