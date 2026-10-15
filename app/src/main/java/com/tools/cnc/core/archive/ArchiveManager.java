package com.tools.cnc.core.archive;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Single entry point for all archive operations.
 *
 * <p>{@link ArchiveManager} inspects the file extension of the target archive
 * and delegates to either {@link ZipHandler} (for {@code .zip} files) or
 * {@link RarHandler} (for {@code .rar} files). Both handlers run their work on
 * a background thread and deliver {@link ArchiveCallback} events on the main
 * thread.
 *
 * <p>Typical usage:
 * <pre>{@code
 * ArchiveManager manager = new ArchiveManager();
 *
 * // Compress
 * manager.compress(files, outputZip, "secret", 6, callback);
 *
 * // Decompress
 * manager.decompress(archiveFile, outputDir, null, callback);
 *
 * // Check encryption
 * boolean locked = manager.isEncrypted(archiveFile);
 * }</pre>
 */
public class ArchiveManager {

    private static final String EXT_ZIP = "zip";
    private static final String EXT_RAR = "rar";

    private final ZipHandler zipHandler;
    private final RarHandler rarHandler;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** Creates an {@code ArchiveManager} with default handler instances. */
    public ArchiveManager() {
        this.zipHandler = new ZipHandler();
        this.rarHandler = new RarHandler();
    }

    /**
     * Creates an {@code ArchiveManager} with caller-supplied handler instances.
     * Useful for testing.
     *
     * @param zipHandler ZIP handler to use.
     * @param rarHandler RAR handler to use.
     */
    public ArchiveManager(ZipHandler zipHandler, RarHandler rarHandler) {
        this.zipHandler = zipHandler;
        this.rarHandler = rarHandler;
    }

    // -------------------------------------------------------------------------
    // Compression
    // -------------------------------------------------------------------------

    /**
     * Compresses a list of files into a ZIP archive.
     *
     * <p>RAR compression is not supported; calling this with a {@code .rar}
     * output path will report an error via {@code callback.onError()}.
     *
     * @param files            Files (or directories) to include.
     * @param outputZip        Destination archive file. Must have a {@code .zip}
     *                         extension.
     * @param password         Optional AES-256 password, or {@code null} / empty
     *                         for no encryption.
     * @param compressionLevel Compression level [0–9]; 0 = store, 9 = maximum.
     * @param callback         Event receiver; must not be {@code null}.
     */
    public void compress(
            List<File> files,
            File outputZip,
            String password,
            int compressionLevel,
            ArchiveCallback callback) {

        if (outputZip == null) {
            callback.onError("Output file must not be null.");
            return;
        }

        String ext = getExtension(outputZip);
        if (!EXT_ZIP.equals(ext)) {
            callback.onError("Compression is only supported for ZIP archives. Got extension: ." + ext);
            return;
        }

        zipHandler.compress(files, outputZip, password, compressionLevel, callback);
    }

    /**
     * Convenience overload that compresses a single file.
     *
     * @see #compress(List, File, String, int, ArchiveCallback)
     */
    public void compress(
            File file,
            File outputZip,
            String password,
            int compressionLevel,
            ArchiveCallback callback) {

        compress(Collections.singletonList(file), outputZip, password, compressionLevel, callback);
    }

    // -------------------------------------------------------------------------
    // Decompression
    // -------------------------------------------------------------------------

    /**
     * Extracts an archive to the specified output directory.
     *
     * <p>Supported formats: {@code .zip}, {@code .rar}.
     *
     * @param archiveFile Archive to extract.
     * @param outputDir   Destination directory (created if absent).
     * @param password    Optional password, or {@code null} / empty string for
     *                    unencrypted archives.
     * @param callback    Event receiver; must not be {@code null}.
     */
    public void decompress(
            File archiveFile,
            File outputDir,
            String password,
            ArchiveCallback callback) {

        if (archiveFile == null) {
            callback.onError("Archive file must not be null.");
            return;
        }

        String ext = getExtension(archiveFile);
        switch (ext) {
            case EXT_ZIP:
                zipHandler.decompress(archiveFile, outputDir, password, callback);
                break;
            case EXT_RAR:
                rarHandler.decompress(archiveFile, outputDir, password, callback);
                break;
            default:
                callback.onError("Unsupported archive format: ." + ext);
        }
    }

    // -------------------------------------------------------------------------
    // Encryption check
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the archive is password-protected.
     *
     * <p>This is a synchronous, lightweight check. For ZIP files it reads only
     * the central directory; for RAR files it scans file headers. Either way
     * it should be fast enough to call on a background thread if needed.
     *
     * @param archiveFile The archive to inspect.
     * @return {@code true} if encrypted, {@code false} if not or on any error.
     */
    public boolean isEncrypted(File archiveFile) {
        if (archiveFile == null || !archiveFile.exists()) {
            return false;
        }

        String ext = getExtension(archiveFile);
        switch (ext) {
            case EXT_ZIP:
                return zipHandler.isEncrypted(archiveFile);
            case EXT_RAR:
                return rarHandler.isEncrypted(archiveFile);
            default:
                return false;
        }
    }

    // -------------------------------------------------------------------------
    // Format support query
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code archiveFile}'s extension is handled by this
     * manager.
     *
     * @param archiveFile File to test.
     * @return {@code true} for {@code .zip} and {@code .rar} files.
     */
    public boolean isSupported(File archiveFile) {
        if (archiveFile == null) {
            return false;
        }
        String ext = getExtension(archiveFile);
        return EXT_ZIP.equals(ext) || EXT_RAR.equals(ext);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts the lowercase file extension of {@code file} without the leading
     * dot, or returns an empty string if no extension is present.
     */
    private static String getExtension(File file) {
        String name = file.getName();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == name.length() - 1) {
            return "";
        }
        return name.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
