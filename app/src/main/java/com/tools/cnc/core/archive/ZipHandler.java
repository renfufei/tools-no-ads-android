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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles ZIP archive creation and extraction using the zip4j library.
 *
 * <p>All I/O operations run on a dedicated background thread managed by an
 * {@link ExecutorService}. Progress and result callbacks are always delivered on
 * the Android main thread via a {@link Handler}.
 *
 * <p>Extraction is performed entry by entry with streams owned by this class rather
 * than through {@code ZipFile.extractFile()}: that keeps every destination path under
 * our control and turns failures into actionable messages (missing permissions, an
 * entry clashing with an existing folder, a wrong password, …).
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
     * <p>Entries are added one by one, synchronously, on the worker thread. zip4j's own
     * run-in-thread mode is deliberately not used: it rejects any new operation while the
     * previous one is still running ("Zip4j is in busy state"), which broke every archive
     * containing more than one entry. Progress is polled from a separate thread instead.
     *
     * @param files            Files (or folders) to include in the archive.
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
            postError(callback, "待压缩的文件列表为空。");
            return;
        }
        if (outputZip == null) {
            postError(callback, "输出文件路径不能为空。");
            return;
        }

        executor.execute(() -> {
            ProgressPoller poller = null;
            try {
                // Delete any pre-existing archive so zip4j always starts fresh.
                if (outputZip.exists() && !outputZip.delete()) {
                    postError(callback, "无法覆盖已存在的文件：" + outputZip.getPath());
                    return;
                }

                boolean usePassword = password != null && !password.isEmpty();

                ZipParameters params = buildZipParameters(compressionLevel, usePassword);

                ZipFile zipFile = usePassword
                        ? new ZipFile(outputZip, password.toCharArray())
                        : new ZipFile(outputZip);

                // Blocking add calls; progress is reported by the poller below.
                zipFile.setRunInThread(false);

                long[] sizes = new long[files.size()];
                long totalBytes = 0;
                for (int i = 0; i < files.size(); i++) {
                    sizes[i] = Math.max(1, sizeOf(files.get(i)));
                    totalBytes += sizes[i];
                }

                poller = new ProgressPoller(zipFile, totalBytes, callback);
                poller.start();

                long doneBytes = 0;
                for (int i = 0; i < files.size(); i++) {
                    File file = files.get(i);
                    poller.onEntryStarted(doneBytes, sizes[i]);

                    if (file.isDirectory()) {
                        zipFile.addFolder(file, params);
                    } else {
                        zipFile.addFile(file, params);
                    }

                    doneBytes += sizes[i];
                }

                poller.stopPolling();
                poller = null;

                postProgress(callback, 100, "");
                postSuccess(callback, outputZip.getAbsolutePath());

            } catch (ZipException e) {
                postError(callback, "压缩失败：" + ArchiveIO.describe(e));
            } catch (Throwable t) {
                postError(callback, "压缩失败：" + ArchiveIO.describe(t));
            } finally {
                if (poller != null) {
                    poller.stopPolling();
                }
            }
        });
    }

    /**
     * Extracts a ZIP archive to the specified output directory.
     *
     * <p>Zip Slip protection is applied: an entry whose canonical path does not start
     * with the canonical path of {@code outputDir} is skipped instead of aborting the
     * whole extraction.
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
            postError(callback, "压缩包不存在："
                    + (zipFile != null ? zipFile.getPath() : "null"));
            return;
        }
        if (zipFile.isDirectory()) {
            postError(callback, "所选路径是一个文件夹，不是压缩包：\n" + zipFile.getPath());
            return;
        }
        if (outputDir == null) {
            postError(callback, "输出目录不能为空。");
            return;
        }

        executor.execute(() -> {
            String currentEntry = null;
            try {
                if (!outputDir.exists() && !outputDir.mkdirs()) {
                    postError(callback, "无法创建输出目录：\n" + outputDir.getAbsolutePath()
                            + "\n\n请检查存储权限，或换一个输出目录。");
                    return;
                }
                if (!ArchiveIO.isWritable(outputDir)) {
                    postError(callback, "输出目录不可写：\n" + outputDir.getAbsolutePath()
                            + "\n\n常见原因：\n"
                            + "· 应用没有存储权限（Android 11 及以上需在系统设置中授予「所有文件访问」权限）\n"
                            + "· 目标目录属于其他应用或系统保护目录\n"
                            + "· 存储空间不足\n\n"
                            + "请更换输出目录后重试。");
                    return;
                }

                boolean usePassword = password != null && !password.isEmpty();

                ZipFile zip = usePassword
                        ? new ZipFile(zipFile, password.toCharArray())
                        : new ZipFile(zipFile);

                if (!zip.isValidZipFile()) {
                    postError(callback, "文件不是有效的 ZIP 压缩包，或文件已损坏：\n"
                            + zipFile.getAbsolutePath()
                            + "\n\n请确认文件完整（大小：" + zipFile.length() + " 字节）。");
                    return;
                }
                if (zip.isEncrypted() && !usePassword) {
                    postError(callback, "该压缩包已加密，请输入密码后重试。");
                    return;
                }

                List<FileHeader> headers = zip.getFileHeaders();
                if (headers == null || headers.isEmpty()) {
                    postProgress(callback, 100, "");
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
                    String entryName = ArchiveIO.normalizeEntryName(header.getFileName());
                    currentEntry = entryName;
                    if (entryName.isEmpty()) {
                        processed++;
                        continue;
                    }

                    File destFile = new File(outputDir, entryName);

                    // --- Zip Slip protection ---
                    String canonicalDest = destFile.getCanonicalPath();
                    if (!canonicalDest.startsWith(canonicalOutputDir)) {
                        processed++;
                        continue;
                    }

                    if (header.isDirectory() || entryName.endsWith("/")) {
                        if (!destFile.exists() && !destFile.mkdirs()) {
                            postError(callback, "无法创建目录：\n" + destFile.getAbsolutePath());
                            return;
                        }
                    } else {
                        File parent = destFile.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            postError(callback, "无法创建目录：\n" + parent.getAbsolutePath());
                            return;
                        }
                        if (destFile.isDirectory()) {
                            postError(callback, "解压失败：压缩包中的文件「" + entryName
                                    + "」与已存在的同名文件夹冲突。\n目标路径："
                                    + destFile.getAbsolutePath()
                                    + "\n\n请删除或重命名该文件夹，或更换输出文件夹名后重试。");
                            return;
                        }

                        try (InputStream in = zip.getInputStream(header);
                             OutputStream out = new FileOutputStream(destFile)) {
                            ArchiveIO.copy(in, out);
                        }
                    }

                    processed++;
                    int pct = (int) ((processed / (double) total) * 100);
                    postProgress(callback, pct, entryName);
                }

                postProgress(callback, 100, "");
                postSuccess(callback, outputDir.getAbsolutePath());

            } catch (ZipException e) {
                postError(callback, describeZipException(e, currentEntry));
            } catch (FileNotFoundException e) {
                postError(callback, "解压失败：目标文件无法写入。\n条目："
                        + describeEntry(currentEntry)
                        + "\n\n" + ArchiveIO.describe(e)
                        + "\n\n提示：若目标目录已有同名文件夹，请清理后重试；"
                        + "若提示权限不足，请在系统设置中授予「所有文件访问」权限。");
            } catch (IOException e) {
                postError(callback, "解压失败（读写错误）。\n条目："
                        + describeEntry(currentEntry)
                        + "\n\n" + ArchiveIO.describe(e));
            } catch (Throwable t) {
                postError(callback, "解压失败。\n条目：" + describeEntry(currentEntry)
                        + "\n\n" + ArchiveIO.describe(t));
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
        } catch (Throwable e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Turns a zip4j failure into something the user can act on. */
    private String describeZipException(ZipException e, String entryName) {
        String detail = ArchiveIO.describe(e);
        String lower = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);

        if (e.getType() == ZipException.Type.WRONG_PASSWORD || lower.contains("wrong password")) {
            return "密码错误，无法解压。\n\n请确认压缩包密码后重试。\n条目：" + describeEntry(entryName);
        }
        if (e.getType() == ZipException.Type.CHECKSUM_MISMATCH) {
            return "解压校验失败（CRC 错误），文件可能已损坏。\n条目：" + describeEntry(entryName)
                    + "\n\n" + detail;
        }
        if (e.getType() == ZipException.Type.UNSUPPORTED_ENCRYPTION) {
            return "该压缩包使用了不支持的加密方式（如 ZIP 传统加密的变种），无法解压。\n条目："
                    + describeEntry(entryName) + "\n\n" + detail;
        }
        if (e.getType() == ZipException.Type.UNKNOWN_COMPRESSION_METHOD) {
            return "该条目使用了不支持的压缩算法。\n条目：" + describeEntry(entryName)
                    + "\n\n" + detail;
        }
        if (e.getType() == ZipException.Type.FILE_NOT_FOUND) {
            return "解压失败：找不到目标文件或路径不可写。\n条目：" + describeEntry(entryName)
                    + "\n\n" + detail;
        }
        return "解压失败。\n条目：" + describeEntry(entryName) + "\n\n" + detail;
    }

    private static String describeEntry(String entryName) {
        return (entryName == null || entryName.isEmpty()) ? "（未知）" : entryName;
    }

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

    /** Recursive size of a file or folder, used to weight the progress of each entry. */
    private static long sizeOf(File file) {
        if (file == null) {
            return 0;
        }
        if (file.isDirectory()) {
            long total = 0;
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    total += sizeOf(child);
                }
            }
            return total;
        }
        return file.length();
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

    /**
     * Polls zip4j's {@link ProgressMonitor} from its own thread while the worker thread runs
     * the blocking compression calls, and reports the percentage across the whole archive.
     */
    private final class ProgressPoller extends Thread {

        private final ZipFile zipFile;
        private final long totalBytes;
        private final ArchiveCallback callback;

        private volatile long baseBytes;
        private volatile long currentBytes = 1;
        private volatile boolean running = true;

        ProgressPoller(ZipFile zipFile, long totalBytes, ArchiveCallback callback) {
            super("zip-progress");
            this.zipFile = zipFile;
            this.totalBytes = Math.max(1, totalBytes);
            this.callback = callback;
            setDaemon(true);
        }

        /** Called before each entry is added: bytes already done + size of the current entry. */
        void onEntryStarted(long baseBytes, long currentBytes) {
            this.baseBytes = baseBytes;
            this.currentBytes = Math.max(1, currentBytes);
        }

        void stopPolling() {
            running = false;
            interrupt();
        }

        @Override
        public void run() {
            while (running) {
                ProgressMonitor monitor = zipFile.getProgressMonitor();
                long done = baseBytes + (long) (currentBytes * (monitor.getPercentDone() / 100.0));
                int overall = (int) Math.min(99, (done * 100) / totalBytes);
                String current = monitor.getFileName();
                postProgress(callback, overall, current != null ? current : "");

                try {
                    Thread.sleep(PROGRESS_POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
