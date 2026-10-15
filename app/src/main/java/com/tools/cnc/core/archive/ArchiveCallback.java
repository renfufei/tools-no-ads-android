package com.tools.cnc.core.archive;

/**
 * Callback interface for archive compress/decompress operations.
 * All methods are invoked on the main (UI) thread.
 */
public interface ArchiveCallback {

    /**
     * Called periodically to report operation progress.
     *
     * @param percentage  Completion percentage in the range [0, 100].
     * @param currentFile Name of the file currently being processed, or an empty
     *                    string if not applicable.
     */
    void onProgress(int percentage, String currentFile);

    /**
     * Called once the operation finishes successfully.
     *
     * @param outputPath Absolute path of the produced output (archive file for
     *                   compression, output directory for decompression).
     */
    void onSuccess(String outputPath);

    /**
     * Called when the operation fails for any reason.
     *
     * @param message Human-readable description of the error.
     */
    void onError(String message);
}
