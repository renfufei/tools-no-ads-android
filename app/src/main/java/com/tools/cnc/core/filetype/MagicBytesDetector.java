package com.tools.cnc.core.filetype;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Detects the true type of a file by reading its magic bytes and matching
 * them against the {@link FileSignature} database.
 *
 * <p>Usage:
 * <pre>
 *     // From a java.io.File (local storage)
 *     MagicBytesDetector.DetectionResult result =
 *             MagicBytesDetector.detectType(file);
 *
 *     // From an InputStream (SAF / ContentResolver URI)
 *     try (InputStream is = getContentResolver().openInputStream(uri)) {
 *         MagicBytesDetector.DetectionResult result =
 *                 MagicBytesDetector.detectType(is);
 *     }
 *
 *     if (result != null) {
 *         Log.d("Type", result.mimeType + " (" + result.extension + ")");
 *     }
 * </pre>
 */
public final class MagicBytesDetector {

    /**
     * Number of header bytes to read.
     * <p>
     * 262 bytes is sufficient to cover:
     * <ul>
     *   <li>The TAR "ustar" magic at offset 257 (257 + 5 = 262 bytes needed)</li>
     *   <li>All other signatures which start at offset 0 or 4/8</li>
     * </ul>
     */
    public static final int HEADER_SIZE = 262;

    // Private constructor – all methods are static.
    private MagicBytesDetector() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Detects the file type of a local {@link File} by reading its first
     * {@value #HEADER_SIZE} bytes.
     *
     * @param file the file to inspect; must be readable
     * @return a {@link DetectionResult}, or {@code null} if the file cannot be
     *         read or no signature matches
     */
    public static DetectionResult detectType(File file) {
        if (file == null || !file.isFile() || !file.canRead()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            return detectType(fis);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Detects the file type by reading up to {@value #HEADER_SIZE} bytes from
     * the supplied {@link InputStream}.  The stream is read but <em>not</em>
     * closed by this method.
     *
     * <p>Suitable for use with SAF / {@code ContentResolver#openInputStream}.
     *
     * @param is an open, positioned-at-start input stream
     * @return a {@link DetectionResult}, or {@code null} if the stream cannot
     *         be read or no signature matches
     */
    public static DetectionResult detectType(InputStream is) {
        if (is == null) {
            return null;
        }
        byte[] header = new byte[HEADER_SIZE];
        int bytesRead = 0;
        try {
            // Read in a loop – a single read() call may return fewer bytes
            // than requested even when more data is available.
            int n;
            while (bytesRead < HEADER_SIZE
                    && (n = is.read(header, bytesRead, HEADER_SIZE - bytesRead)) != -1) {
                bytesRead += n;
            }
        } catch (IOException e) {
            return null;
        }

        if (bytesRead == 0) {
            return null;
        }

        FileSignature sig = FileSignature.match(header, bytesRead);
        if (sig == null) {
            return null;
        }

        return new DetectionResult(sig, bytesRead);
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /**
     * Immutable result object returned by the detection methods.
     */
    public static final class DetectionResult {

        /** IANA media type (e.g. {@code "image/jpeg"}). Never {@code null}. */
        public final String mimeType;

        /** Human-readable description (e.g. {@code "JPEG Image"}). Never {@code null}. */
        public final String description;

        /**
         * Canonical file extension without leading dot (e.g. {@code "jpg"}).
         * Never {@code null}.
         */
        public final String extension;

        /**
         * Confidence level of the detection.
         * <ul>
         *   <li>{@link Confidence#HIGH} – the full magic-byte sequence matched</li>
         *   <li>{@link Confidence#MEDIUM} – matched but fewer bytes were available
         *       than the full header size (small or truncated file)</li>
         * </ul>
         */
        public final Confidence confidence;

        /** The matched {@link FileSignature} entry (for advanced consumers). */
        public final FileSignature signature;

        DetectionResult(FileSignature signature, int bytesRead) {
            this.signature   = signature;
            this.mimeType    = signature.mimeType;
            this.description = signature.description;
            this.extension   = signature.extension;
            this.confidence  = (bytesRead >= HEADER_SIZE)
                               ? Confidence.HIGH
                               : Confidence.MEDIUM;
        }

        /**
         * Returns {@code true} if the detected MIME type starts with
         * {@code "image/"}.
         */
        public boolean isImage() {
            return mimeType.startsWith("image/");
        }

        /**
         * Returns {@code true} if the detected MIME type starts with
         * {@code "video/"}.
         */
        public boolean isVideo() {
            return mimeType.startsWith("video/");
        }

        /**
         * Returns {@code true} if the detected MIME type starts with
         * {@code "audio/"}.
         */
        public boolean isAudio() {
            return mimeType.startsWith("audio/");
        }

        @Override
        public String toString() {
            return "DetectionResult{"
                    + "mimeType='" + mimeType + '\''
                    + ", extension='" + extension + '\''
                    + ", description='" + description + '\''
                    + ", confidence=" + confidence
                    + '}';
        }
    }

    // -------------------------------------------------------------------------
    // Confidence enum
    // -------------------------------------------------------------------------

    /**
     * Confidence level of a magic-bytes detection result.
     */
    public enum Confidence {

        /**
         * The full {@value MagicBytesDetector#HEADER_SIZE}-byte header was
         * available and the signature matched.  This is the normal case for
         * files larger than {@value MagicBytesDetector#HEADER_SIZE} bytes.
         */
        HIGH,

        /**
         * Fewer than {@value MagicBytesDetector#HEADER_SIZE} bytes were
         * available (very small or truncated file/stream), but the signature
         * still matched within the available bytes.
         */
        MEDIUM,
    }
}
