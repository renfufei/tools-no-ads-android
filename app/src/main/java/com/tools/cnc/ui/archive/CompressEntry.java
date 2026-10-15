package com.tools.cnc.ui.archive;

import android.net.Uri;

/**
 * One item queued for compression. The entry always keeps the original {@link Uri};
 * the compression step compresses {@code file://} entries in place and only copies
 * {@code content://} entries into the app cache.
 */
public class CompressEntry {

    private final Uri uri;
    private final String displayName;
    private final long fileSize;
    private final boolean directory;

    public CompressEntry(Uri uri, String displayName, long fileSize, boolean directory) {
        this.uri = uri;
        this.displayName = displayName;
        this.fileSize = fileSize;
        this.directory = directory;
    }

    public Uri getUri() {
        return uri;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Size in bytes; for a directory this is the recursive size of its contents. */
    public long getFileSize() {
        return fileSize;
    }

    public boolean isDirectory() {
        return directory;
    }
}
