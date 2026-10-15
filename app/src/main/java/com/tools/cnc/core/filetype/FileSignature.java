package com.tools.cnc.core.filetype;

/**
 * Represents a known file format identified by its magic bytes (file signature).
 * <p>
 * The static {@link #SIGNATURES} list contains entries for 30+ common formats.
 * Use {@link #match(byte[], int)} to identify a file from its header bytes.
 */
public class FileSignature {

    /** Raw magic bytes to match against the file header. */
    public final byte[] magic;

    /**
     * Byte offset within the file at which {@link #magic} must appear.
     * Most signatures start at offset 0; TAR uses offset 257.
     */
    public final int offset;

    /** IANA media type (e.g. {@code "image/jpeg"}). */
    public final String mimeType;

    /** Human-readable description (e.g. {@code "JPEG Image"}). */
    public final String description;

    /** Canonical file extension without a leading dot (e.g. {@code "jpg"}). */
    public final String extension;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public FileSignature(byte[] magic, int offset, String mimeType,
                         String description, String extension) {
        this.magic       = magic;
        this.offset      = offset;
        this.mimeType    = mimeType;
        this.description = description;
        this.extension   = extension;
    }

    /**
     * Convenience constructor that accepts a hex string such as
     * {@code "FF D8 FF"} (spaces optional) instead of a raw {@code byte[]}.
     */
    public FileSignature(String hexMagic, int offset, String mimeType,
                         String description, String extension) {
        this(hexToBytes(hexMagic), offset, mimeType, description, extension);
    }

    // -------------------------------------------------------------------------
    // Known signatures
    // -------------------------------------------------------------------------

    /**
     * Ordered list of known file signatures.
     * <p>
     * Longer / more-specific signatures are listed before shorter ones so that
     * {@link #match} returns the most precise result when multiple entries
     * share the same offset prefix (e.g. RAR5 before RAR4, GIF89a before
     * GIF87a, WAV/AVI/WebP before generic RIFF).
     */
    public static final FileSignature[] SIGNATURES = new FileSignature[] {

        // ── Images ──────────────────────────────────────────────────────────

        new FileSignature("FF D8 FF",                  0, "image/jpeg",    "JPEG Image",       "jpg"),
        new FileSignature("89 50 4E 47 0D 0A 1A 0A",  0, "image/png",     "PNG Image",        "png"),
        new FileSignature("47 49 46 38 39 61",         0, "image/gif",     "GIF Image",        "gif"),  // GIF89a
        new FileSignature("47 49 46 38 37 61",         0, "image/gif",     "GIF Image",        "gif"),  // GIF87a
        new FileSignature("42 4D",                     0, "image/bmp",     "BMP Image",        "bmp"),
        new FileSignature("49 49 2A 00",               0, "image/tiff",    "TIFF Image",       "tiff"), // little-endian
        new FileSignature("4D 4D 00 2A",               0, "image/tiff",    "TIFF Image",       "tiff"), // big-endian

        // WebP  – RIFF....WEBP  (magic split: 4 bytes @ 0, then 4 bytes @ 8)
        new WebpSignature(),

        // HEIC/HEIF – 'ftyp' box at offset 4, then brand bytes at offset 8
        new HeicSignature(),

        // ── Documents ───────────────────────────────────────────────────────

        new FileSignature("25 50 44 46",               0, "application/pdf",  "PDF Document",  "pdf"),

        // ── Archives ────────────────────────────────────────────────────────

        // ZIP (also base for DOCX/XLSX/PPTX/APK/JAR – kept as plain zip here)
        new FileSignature("50 4B 03 04",               0, "application/zip",  "ZIP Archive",   "zip"),

        // RAR5 must come before RAR4 (RAR5 is a longer prefix)
        new FileSignature("52 61 72 21 1A 07 01 00",   0, "application/x-rar-compressed", "RAR Archive (v5)", "rar"),
        new FileSignature("52 61 72 21 1A 07 00",      0, "application/x-rar-compressed", "RAR Archive (v4)", "rar"),

        new FileSignature("37 7A BC AF 27 1C",         0, "application/x-7z-compressed",  "7-Zip Archive",   "7z"),
        new FileSignature("1F 8B",                     0, "application/gzip",              "Gzip Archive",    "gz"),

        // TAR – "ustar" magic at byte offset 257
        new FileSignature("75 73 74 61 72",          257, "application/x-tar", "TAR Archive",  "tar"),

        // ── Audio ────────────────────────────────────────────────────────────

        new FileSignature("49 44 33",                  0, "audio/mpeg",   "MP3 Audio (ID3)",  "mp3"),
        new FileSignature("FF FB",                     0, "audio/mpeg",   "MP3 Audio",        "mp3"),
        new FileSignature("FF F3",                     0, "audio/mpeg",   "MP3 Audio",        "mp3"),
        new FileSignature("FF F2",                     0, "audio/mpeg",   "MP3 Audio",        "mp3"),
        new FileSignature("66 4C 61 43",               0, "audio/flac",   "FLAC Audio",       "flac"),
        new FileSignature("4F 67 67 53",               0, "audio/ogg",    "OGG Audio",        "ogg"),

        // WAV  – RIFF....WAVE
        new RiffSignature("57 41 56 45", "audio/wav",   "WAV Audio",  "wav"),
        // AVI  – RIFF....AVI\x20
        new RiffSignature("41 56 49 20", "video/x-msvideo", "AVI Video", "avi"),

        // ── Video ────────────────────────────────────────────────────────────

        // MP4/M4A/M4V – ISO base media file format ('ftyp' at offset 4)
        new Mp4Signature(),

        // MKV / WebM
        new FileSignature("1A 45 DF A3",              0, "video/x-matroska", "MKV/WebM Video", "mkv"),

        new FileSignature("46 4C 56",                  0, "video/x-flv",     "FLV Video",      "flv"),

        // ── Executables / Bytecode ───────────────────────────────────────────

        new FileSignature("4D 5A",                     0, "application/x-msdownload", "Windows Executable", "exe"),
        new FileSignature("7F 45 4C 46",               0, "application/x-elf",        "ELF Executable",     "elf"),
        new FileSignature("CA FE BA BE",               0, "application/java-vm",       "Java Class File",    "class"),
        new FileSignature("64 65 78 0A",               0, "application/vnd.android.dex", "Android DEX",     "dex"),

        // ── Data / Markup ────────────────────────────────────────────────────

        new FileSignature("53 51 4C 69 74 65",         0, "application/x-sqlite3", "SQLite Database", "db"),
        new FileSignature("3C 3F 78 6D 6C",            0, "application/xml",       "XML Document",    "xml"),
    };

    // -------------------------------------------------------------------------
    // Matching
    // -------------------------------------------------------------------------

    /**
     * Returns the first {@link FileSignature} in {@link #SIGNATURES} whose
     * magic bytes match the supplied header, or {@code null} if no entry
     * matches.
     *
     * @param header       raw bytes read from the beginning of the file
     * @param headerLength number of valid bytes in {@code header}
     */
    public static FileSignature match(byte[] header, int headerLength) {
        if (header == null || headerLength <= 0) {
            return null;
        }
        for (FileSignature sig : SIGNATURES) {
            if (sig.matches(header, headerLength)) {
                return sig;
            }
        }
        return null;
    }

    /**
     * Tests whether this signature matches the given header bytes.
     * Subclasses may override this for multi-part signatures (e.g. RIFF
     * containers, ISO base-media files).
     */
    public boolean matches(byte[] header, int headerLength) {
        if (offset + magic.length > headerLength) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (header[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Converts a hex string (bytes separated by optional spaces) to a
     * {@code byte[]}. E.g. {@code "FF D8 FF"} → {@code {0xFF, 0xD8, 0xFF}}.
     */
    public static byte[] hexToBytes(String hex) {
        String clean = hex.replace(" ", "");
        if (clean.length() % 2 != 0) {
            throw new IllegalArgumentException("Odd-length hex string: " + hex);
        }
        byte[] result = new byte[clean.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    // =========================================================================
    // Inner classes for multi-part / container signatures
    // =========================================================================

    // ── RIFF container (WAV, AVI) ─────────────────────────────────────────────

    /**
     * Matches RIFF-container files by checking both the 4-byte RIFF marker at
     * offset 0 and the 4-byte sub-type at offset 8 (e.g. "WAVE" or "AVI ").
     */
    static final class RiffSignature extends FileSignature {

        private final byte[] subType;

        RiffSignature(String subTypeHex, String mimeType,
                      String description, String extension) {
            super(hexToBytes("52 49 46 46"), 0, mimeType, description, extension);
            this.subType = hexToBytes(subTypeHex);
        }

        @Override
        public boolean matches(byte[] header, int headerLength) {
            // Need at least 12 bytes: RIFF (4) + size (4) + sub-type (4)
            if (headerLength < 12) return false;
            // Check "RIFF" at offset 0
            if (!super.matches(header, headerLength)) return false;
            // Check sub-type at offset 8
            for (int i = 0; i < subType.length; i++) {
                if (header[8 + i] != subType[i]) return false;
            }
            return true;
        }
    }

    // ── WebP ──────────────────────────────────────────────────────────────────

    /**
     * WebP files start with "RIFF" at offset 0, have a 4-byte file size field,
     * then "WEBP" at offset 8.
     */
    static final class WebpSignature extends FileSignature {

        private static final byte[] RIFF = hexToBytes("52 49 46 46");
        private static final byte[] WEBP = hexToBytes("57 45 42 50");

        WebpSignature() {
            super(RIFF, 0, "image/webp", "WebP Image", "webp");
        }

        @Override
        public boolean matches(byte[] header, int headerLength) {
            if (headerLength < 12) return false;
            for (int i = 0; i < RIFF.length; i++) {
                if (header[i] != RIFF[i]) return false;
            }
            for (int i = 0; i < WEBP.length; i++) {
                if (header[8 + i] != WEBP[i]) return false;
            }
            return true;
        }
    }

    // ── ISO Base Media / MP4 family ───────────────────────────────────────────

    /**
     * MP4, M4A, M4V and related files follow the ISO base media file format.
     * The 4-byte box type "ftyp" appears at byte offset 4; the 4-byte brand
     * starts at offset 8.  We recognise the brands most commonly seen on
     * Android: isom, mp41, mp42, M4A , M4V , f4v , dash, iso2.
     */
    static final class Mp4Signature extends FileSignature {

        private static final byte[] FTYP = hexToBytes("66 74 79 70"); // "ftyp"

        // Each entry is a 4-byte ASCII brand
        private static final String[] BRANDS = {
            "isom", "mp41", "mp42", "M4A ", "M4V ", "f4v ", "dash", "iso2",
            "avc1", "iso4", "iso5", "iso6", "mmp4", "MSNV", "NDAS", "NDSC",
        };

        Mp4Signature() {
            super(FTYP, 4, "video/mp4", "MP4 Video", "mp4");
        }

        @Override
        public boolean matches(byte[] header, int headerLength) {
            // Need at least 12 bytes for ftyp (offset 4..7) + brand (offset 8..11)
            if (headerLength < 12) return false;
            // "ftyp" at offset 4
            for (int i = 0; i < FTYP.length; i++) {
                if (header[4 + i] != FTYP[i]) return false;
            }
            // Read the 4-byte brand at offset 8
            String brand = new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            for (String b : BRANDS) {
                if (b.equals(brand)) return true;
            }
            return false;
        }
    }

    // ── HEIC / HEIF ──────────────────────────────────────────────────────────

    /**
     * HEIC/HEIF files also use an ISO base-media 'ftyp' box (offset 4) but
     * with brands: heic, heix, mif1, hevc, hevx, heim, heis, hevm, hevs.
     */
    static final class HeicSignature extends FileSignature {

        private static final byte[] FTYP = hexToBytes("66 74 79 70");

        private static final String[] HEIC_BRANDS = {
            "heic", "heix", "mif1", "hevc", "hevx", "heim", "heis", "hevm", "hevs",
        };

        HeicSignature() {
            super(FTYP, 4, "image/heic", "HEIC/HEIF Image", "heic");
        }

        @Override
        public boolean matches(byte[] header, int headerLength) {
            if (headerLength < 12) return false;
            for (int i = 0; i < FTYP.length; i++) {
                if (header[4 + i] != FTYP[i]) return false;
            }
            String brand = new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            for (String b : HEIC_BRANDS) {
                if (b.equals(brand)) return true;
            }
            return false;
        }
    }
}
