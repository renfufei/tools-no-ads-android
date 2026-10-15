package com.tools.cnc.ui.archive;

import android.net.Uri;

import java.io.File;

public class CompressEntry {
    private final Uri uri;
    private final String displayName;
    private final long fileSize;
    private File cachedFile;

    public CompressEntry(Uri uri, String displayName, long fileSize) {
        this.uri = uri;
        this.displayName = displayName;
        this.fileSize = fileSize;
    }

    public Uri getUri() {
        return uri;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public File getCachedFile() {
        return cachedFile;
    }

    public void setCachedFile(File cachedFile) {
        this.cachedFile = cachedFile;
    }
}
