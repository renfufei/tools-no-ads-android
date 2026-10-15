package com.tools.cnc.core.archive;

import android.os.Handler;
import android.os.Looper;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import net.lingala.zip4j.progress.ProgressMonitor;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles ZIP archive creation and extraction using the zip4j library.
 *
 * <p>All I/O operations run on a dedicated background thread managed by an
 * {@link ExecutorService}. Progress and result callbacks are always delivered on
 * the Android main thread via a {@link Handler}.
 */
public class ZipHandler {

    private static final int PROGRESS_POLL_INTERVAL_MS = 200;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Compresses a list of files into a ZIP archive.
     *
     * @param files            Files to include in the archive.
     * @param outputZip        Destination ZIP file (will be created or overwritten).
     * @param password         Optional password. Pass {@code null} or an empty string
     *                         to create an unencrypted archive. When non-empty, AES-256
     *                         encryption is applied.
     * @param compressionLevel Compression level in the range [0, 9]. Values outside
     *                         this range are clamped. 0 = store only, 9 = maximum
     *                         compression.
     * @param callback         Receives progress, success, or error events on the main
     *                         thread. Must not be {@code null}.
     */
    public void compress(
            List<File> files,
            File outputZip,
            String password,
            int compressionLevel,
            ArchiveCallback callback) {

        if (files == null || files.isEmpty()) {
            postError(callback, "File list is empty.");
            return;
        }
        if (outputZip == null) {
            postError(callback, "Output ZIP path must not be null.");
            return;
        }

        executor.execute(() -> {
            try {
                // Delete any pre-existing archive so zip4j always starts fresh.
                if (outputZip.exists() && !outputZip.delete()) {
                    postError(callback, "Could not overwrite existing file: " + outputZip.getPath());
                    return;
                }

                boolean usePassword = password != null && !password.isEmpty();

                ZipParameters params = buildZipParameters(compressionLevel, usePassword);

                ZipFile zipFile = usePassword
                        ? new ZipFile(outputZip, password.toCharArray())
                        : new ZipFile(outputZip);

                // Enable run-in-thread mode so we can poll the ProgressMonitor.
                zipFile.setRunInThread(true);

                // Add all files.
                for (File file : files) {
                    if (file.isDirectory()) {
                        zipFile.addFolder(file, params);
                    } else {
                        zipFile.addFile(file, params);
                    }
                }

                // Poll until zip4j finishes the background processing it started.
                ProgressMonitor monitor = zipFile.getProgressMonitor();
                while (!monitor.getState().equals(ProgressMonitor.State.READY)) {
                    int pct = monitor.getPercentDone();
                    String current = monitor.getFileName() != null ? monitor.getFileName() : "";
                    postProgress(callback, pct, current);

                    Thread.sleep(PROGRESS_POLL_INTERVAL_MS);
                }

                if (monitor.getResult().equals(ProgressMonitor.Result.ERROR)) {
                    Exception cause = monitor.getException();
                    postError(callback, cause != null ? cause.getMessage() : "Unknown compression error.");
                    return;
                }

                postProgress(callback, 100, "");
                postSuccess(callback, outputZip.getAbsolutePath());

            } catch (ZipException e) {
                postError(callback, "ZIP error during compression: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                postError(callback, "Compression interrupted.");
            }
        });
    }

    /**
     * Extracts a ZIP archive to the specified output directory.
     *
     * <p>Zip Slip protection is applied: any entry whose canonical path does not
     * start with the canonical path of {@code outputDir} is skipped with a warning
     * and does not cause the entire extraction to fail.
     *
     * @param zipFile   ZIP archive to extract.
     * @param outputDir Directory into which files are extracted (created if absent).
     * @param password  Optional password for encrypted archives. Pass {@code null}
     *                  or an empty string for unencrypted archives.
     * @param callback  Receives progress, success, or error events on the main thread.
     */
    public void decompress(
            File zipFile,
            File outputDir,
            String password,
            ArchiveCallback callback) {

        if (zipFile == null || !zipFile.exists()) {
            postError(callback, "ZIP file does not exist: " + (zipFile != null ? zipFile.getPath() : "null"));
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

                boolean usePassword = password != null && !password.isEmpty();

                ZipFile zip = usePassword
                        ? new ZipFile(zipFile, password.toCharArray())
                        : new ZipFile(zipFile);

                List<FileHeader> headers = zip.getFileHeaders();
                if (headers == null || headers.isEmpty()) {
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
                    String entryName = header.getFileName();

                    // --- Zip Slip protection ---
                    File entryDest = new File(outputDir, entryName);
                    String canonicalEntry = entryDest.getCanonicalPath();
                    if (!canonicalEntry.startsWith(canonicalOutputDir)) {
                        // Skip malicious path traversal entry.
                        processed++;
                        continue;
                    }

                    zip.extractFile(header, outputDir.getAbsolutePath());

                    processed++;
                    int pct = (int) ((processed / (double) total) * 100);
                    postProgress(callback, pct, entryName);
                }

                postProgress(callback, 100, "");
                postSuccess(callback, outputDir.getAbsolutePath());

            } catch (ZipException e) {
                postError(callback, "ZIP error during extraction: " + e.getMessage());
            } catch (IOException e) {
                postError(callback, "I/O error during extraction: " + e.getMessage());
            }
        });
    }

    /**
     * Returns {@code true} if the ZIP archive is password-protected.
     *
     * <p>This is a synchronous, lightweight check and may be called from any thread.
     *
     * @param zipFile The ZIP file to inspect.
     * @return {@code true} if encrypted, {@code false} otherwise or on error.
     */
    public boolean isEncrypted(File zipFile) {
        if (zipFile == null || !zipFile.exists()) {
            return false;
        }
        try {
            ZipFile zip = new ZipFile(zipFile);
            return zip.isEncrypted();
        } catch (ZipException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private ZipParameters buildZipParameters(int compressionLevel, boolean usePassword) {
        ZipParameters params = new ZipParameters();
        params.setCompressionMethod(CompressionMethod.DEFLATE);
        params.setCompressionLevel(mapCompressionLevel(compressionLevel));

        if (usePassword) {
            params.setEncryptFiles(true);
            params.setEncryptionMethod(EncryptionMethod.AES);
            params.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        }

        return params;
    }

    /**
     * Maps an integer compression level [0, 9] to a zip4j {@link CompressionLevel}.
     */
    private CompressionLevel mapCompressionLevel(int level) {
        int clamped = Math.max(0, Math.min(9, level));
        switch (clamped) {
            case 0:  return CompressionLevel.NO_COMPRESSION;
            case 1:  return CompressionLevel.FASTEST;
            case 2:  return CompressionLevel.FASTER;
            case 3:  // fall-through
            case 4:  return CompressionLevel.FAST;
            case 5:  return CompressionLevel.MEDIUM_FAST;
            case 6:  return CompressionLevel.NORMAL;
            case 7:  return CompressionLevel.HIGHER;
            case 8:  return CompressionLevel.MAXIMUM;
            case 9:  return CompressionLevel.ULTRA;
            default: return CompressionLevel.NORMAL;
        }
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
