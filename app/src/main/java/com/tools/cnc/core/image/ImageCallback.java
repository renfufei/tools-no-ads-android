package com.tools.cnc.core.image;

import java.io.File;
import java.util.List;

/**
 * Callback interface for image processing operations.
 * All methods are invoked on the main (UI) thread.
 */
public interface ImageCallback {

    /**
     * Called periodically to report progress of a batch operation.
     *
     * @param current     the number of files processed so far (1-based)
     * @param total       the total number of files in the batch
     * @param currentFile the name of the file currently being processed
     */
    void onProgress(int current, int total, String currentFile);

    /**
     * Called when the entire batch has finished successfully.
     *
     * @param outputFiles an ordered list of every output file that was produced
     */
    void onComplete(List<File> outputFiles);

    /**
     * Called when a fatal error occurs that prevents the operation from continuing.
     *
     * @param message a human-readable description of the error
     */
    void onError(String message);
}
