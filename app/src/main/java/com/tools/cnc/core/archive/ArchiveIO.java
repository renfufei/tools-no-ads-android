package com.tools.cnc.core.archive;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;

/** Small shared helpers for the archive handlers. */
public final class ArchiveIO {

    private static final int BUFFER_SIZE = 8192;

    private ArchiveIO() {
    }

    /**
     * Normalizes an archive entry name into a relative path that is safe to append
     * to a destination folder: Windows separators are converted, drive letters and
     * leading slashes are stripped so the entry can never escape the output folder.
     *
     * @return the normalized name, or an empty string for entries that carry no name.
     */
    public static String normalizeEntryName(String rawName) {
        if (rawName == null) {
            return "";
        }
        String name = rawName.replace('\\', '/').trim();
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        // "C:/..." style absolute paths collapse to their relative part.
        if (name.length() > 2 && name.charAt(1) == ':') {
            name = name.substring(2);
            while (name.startsWith("/")) {
                name = name.substring(1);
            }
        }
        return name;
    }

    /**
     * Verifies that {@code dir} can actually be written to by creating and deleting a
     * probe file. {@link File#canWrite()} alone reports stale results on Android's
     * storage layer and would let extraction fail later with a confusing error.
     */
    public static boolean isWritable(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        File probe = new File(dir, ".cnc_write_probe_" + System.nanoTime());
        try (FileOutputStream out = new FileOutputStream(probe)) {
            out.write(0);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
        }
    }

    /** Copies the whole stream and returns the number of bytes written. */
    public static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    /**
     * Builds a readable description of a failure. Exception messages are frequently
     * {@code null} (junrar's RAR5 rejection does exactly that, which surfaced as
     * "操作失败: null"), so the type name and the cause chain are always included.
     */
    public static String describe(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable current = unwrap(error);
        int depth = 0;
        while (current != null && depth < 4) {
            if (depth > 0) {
                sb.append("\n  ← ");
            }
            sb.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                sb.append(": ").append(message.trim());
            } else {
                sb.append("（无详细信息）");
            }
            current = current.getCause() == current ? null : current.getCause();
            depth++;
        }
        return sb.toString();
    }

    /** Unwraps reflection wrappers so the real error is what gets reported. */
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof InvocationTargetException
                && ((InvocationTargetException) current).getTargetException() != null) {
            current = ((InvocationTargetException) current).getTargetException();
        }
        return current;
    }
}
