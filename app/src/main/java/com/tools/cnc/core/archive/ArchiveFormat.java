package com.tools.cnc.core.archive;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * Sniffs the real container format of an archive from its leading bytes.
 *
 * <p>The file extension is not trustworthy (an {@code .rar} file may be RAR5, which
 * junrar cannot read), so the format is detected before extraction starts and the UI
 * can warn the user up front instead of failing halfway through.
 */
public final class ArchiveFormat {

    public enum Kind {
        /** Local file header / end-of-central-directory / spanned signature. */
        ZIP,
        RAR4,
        /** WinRAR 5.0+ default container: not readable by junrar. */
        RAR5,
        UNKNOWN
    }

    private static final byte[] ZIP_LOCAL = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] ZIP_EMPTY = {0x50, 0x4B, 0x05, 0x06};
    private static final byte[] ZIP_SPANNED = {0x50, 0x4B, 0x07, 0x08};
    private static final byte[] RAR4_MARK = {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00};
    private static final byte[] RAR5_MARK = {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00};

    private ArchiveFormat() {
    }

    /** Reads at most the first 8 bytes; never throws. */
    public static Kind probe(File file) {
        if (file == null || !file.isFile()) {
            return Kind.UNKNOWN;
        }
        byte[] head = new byte[8];
        int read;
        try (InputStream in = new FileInputStream(file)) {
            read = in.read(head);
        } catch (IOException e) {
            return Kind.UNKNOWN;
        }
        if (read <= 0) {
            return Kind.UNKNOWN;
        }
        byte[] actual = read == head.length ? head : Arrays.copyOf(head, read);

        if (startsWith(actual, ZIP_LOCAL)
                || startsWith(actual, ZIP_EMPTY)
                || startsWith(actual, ZIP_SPANNED)) {
            return Kind.ZIP;
        }
        if (startsWith(actual, RAR5_MARK)) {
            return Kind.RAR5;
        }
        if (startsWith(actual, RAR4_MARK)) {
            return Kind.RAR4;
        }
        return Kind.UNKNOWN;
    }

    /** Human-readable label for the UI, based on the detected container. */
    public static String describe(File file) {
        switch (probe(file)) {
            case ZIP:
                return "ZIP";
            case RAR4:
                return "RAR 4.x";
            case RAR5:
                return "RAR 5.x";
            default:
                return labelFromExtension(file);
        }
    }

    /** {@code true} when the file is the RAR5 container that junrar rejects. */
    public static boolean isUnsupportedRar5(File file) {
        return probe(file) == Kind.RAR5;
    }

    private static String labelFromExtension(File file) {
        if (file == null) {
            return "未知格式";
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "未知格式";
        }
        return name.substring(dot + 1).toUpperCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
