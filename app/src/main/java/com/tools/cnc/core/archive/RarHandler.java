package com.tools.cnc.core.archive;

import android.os.Handler;
import android.os.Looper;

import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.FileHeader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles RAR archive extraction using the junrar library.
 *
 * <p>All I/O operations run on a dedicated background thread. Callbacks are
 * always delivered on the Android main thread via a {@link Handler}.
 *
 * <p>Note: junrar supports decompression only. RAR creation is not provided.
 */
public class RarHandler {

    private static final int BUFFER_SIZE = 8192;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Extracts a RAR archive to the specified output directory.
     *
     * <p>Zip Slip protection is applied: any entry whose canonical path does not
     * start with the canonical path of {@code outputDir} is skipped silently and
     * does not abort the remaining extraction.
     *
     * @param rarFile   RAR archive to extract.
     * @param outputDir Directory into which files are extracted (created if absent).
     * @param password  Optional password for encrypted archives. Pass {@code null}
     *                  or an empty string for unencrypted archives.
     * @param callback  Receives progress, success, or error events on the main thread.
     */
    public void decompress(
            File rarFile,
            File outputDir,
            String password,
            ArchiveCallback callback) {

        if (rarFile == null || !rarFile.exists()) {
            postError(callback, "RAR file does not exist: " + (rarFile != null ? rarFile.getPath() : "null"));
            return;
        }
        if (outputDir == null) {
            postError(callback, "Output directory must not be null.");
            return;
        }

        executor.execute(() -> {
            try {
                if (!outputDir.exists() && !outputDir.mkdirs()) {
                    postError(callback, "Could not create output directory: " + outputDir.getPath());
                    return;
                }

                Archive archive = openArchive(rarFile, password);

                List<FileHeader> headers = archive.getFileHeaders();
                if (headers == null || headers.isEmpty()) {
                    archive.close();
                    postSuccess(callback, outputDir.getAbsolutePath());
                    return;
                }

                String canonicalOutputDir = outputDir.getCanonicalPath();
                if (!canonicalOutputDir.endsWith(File.separator)) {
                    canonicalOutputDir += File.separator;
                }

                int total = headers.size();
                int processed = 0;

                for (FileHeader header : headers) {
                    String entryName = resolveEntryName(header);

                    // --- Zip Slip protection ---
                    File destFile = new File(outputDir, entryName);
                    String canonicalDest = destFile.getCanonicalPath();
                    if (!canonicalDest.startsWith(canonicalOutputDir)) {
                        processed++;
                        continue;
                    }

                    if (header.isDirectory()) {
                        if (!destFile.exists() && !destFile.mkdirs()) {
                            postError(callback, "Could not create directory: " + destFile.getPath());
                            archive.close();
                            return;
                        }
                    } else {
                        // Ensure parent directories exist.
                        File parent = destFile.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            postError(callback, "Could not create parent directory: " + parent.getPath());
                            archive.close();
                            return;
                        }

                        extractEntry(archive, header, destFile);
                    }

                    processed++;
                    int pct = (int) ((processed / (double) total) * 100);
                    postProgress(callback, pct, entryName);
                }

                archive.close();
                postProgress(callback, 100, "");
                postSuccess(callback, outputDir.getAbsolutePath());

            } catch (RarException e) {
                postError(callback, "RAR error during extraction: " + e.getMessage());
            } catch (IOException e) {
                postError(callback, "I/O error during extraction: " + e.getMessage());
            }
        });
    }

    /**
     * Returns {@code true} if the RAR archive is password-protected.
     *
     * <p>This is a synchronous check and may be called from any thread.
     *
     * @param rarFile The RAR file to inspect.
     * @return {@code true} if encrypted, {@code false} otherwise or on error.
     */
    public boolean isEncrypted(File rarFile) {
        if (rarFile == null || !rarFile.exists()) {
            return false;
        }
        try {
            Archive archive = new Archive(rarFile);
            List<FileHeader> headers = archive.getFileHeaders();
            boolean encrypted = false;
            if (headers != null) {
                for (FileHeader header : headers) {
                    if (header.isEncrypted()) {
                        encrypted = true;
                        break;
                    }
                }
            }
            archive.close();
            return encrypted;
        } catch (RarException | IOException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Opens the archive with an optional password. junrar accepts the password
     * as a plain {@code String}; passing {@code null} or empty string is safe.
     */
    private Archive openArchive(File rarFile, String password) throws RarException, IOException {
        boolean usePassword = password != null && !password.isEmpty();
        if (usePassword) {
            return new Archive(rarFile, password);
        }
        return new Archive(rarFile);
    }

    /**
     * Extracts a single non-directory entry from the archive to {@code destFile}.
     */
    private void extractEntry(Archive archive, FileHeader header, File destFile)
            throws RarException, IOException {

        try (OutputStream out = new FileOutputStream(destFile);
             InputStream in = archive.getInputStream(header)) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    /**
     * Returns the platform-independent entry name, normalizing RAR's
     * Windows-style backslash separators to the local {@link File#separator}.
     */
    private String resolveEntryName(FileHeader header) {
        // junrar may return Unicode or ASCII file name depending on the archive.
        String name = header.isUnicode()
                ? header.getFileNameW()
                : header.getFileNameString();

        if (name == null) {
            name = "";
        }
        // Normalize Windows path separators.
        return name.replace('\\', File.separatorChar);
    }

    private void postProgress(ArchiveCallback callback, int percentage, String currentFile) {
        mainHandler.post(() -> callback.onProgress(percentage, currentFile));
    }

    private void postSuccess(ArchiveCallback callback, String outputPath) {
        mainHandler.post(() -> callback.onSuccess(outputPath));
    }

    private void postError(ArchiveCallback callback, String message) {
        mainHandler.post(() -> callback.onError(message));
    }
}
