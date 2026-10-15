package com.tools.cnc.core.archive;

import android.os.Handler;
import android.os.Looper;

import com.github.junrar.Archive;
import com.github.junrar.exception.CorruptHeaderException;
import com.github.junrar.exception.CrcErrorException;
import com.github.junrar.exception.RarException;
import com.github.junrar.exception.UnsupportedRarEncryptedException;
import com.github.junrar.exception.UnsupportedRarV5Exception;
import com.github.junrar.rarfile.FileHeader;

import java.io.File;
import java.io.FileNotFoundException;
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
 * <p><b>RAR5 is not supported.</b> junrar only reads the RAR4 container; when it meets
 * a RAR5 file it throws {@code UnsupportedRarV5Exception}, whose message is
 * {@code null} — which used to surface as the useless "RAR error during extraction: null".
 * The container is therefore sniffed up front and reported in plain language.
 *
 * <p>Note: junrar supports decompression only. RAR creation is not provided.
 */
public class RarHandler {

    /** Shown whenever a RAR5 (WinRAR 5.0+) container is encountered. */
    static final String RAR5_UNSUPPORTED_MESSAGE =
            "该文件是 RAR5 格式（WinRAR 5.0 及以上版本的默认格式），"
                    + "当前内置组件只支持解压 RAR4，无法处理该文件。\n\n"
                    + "可选方案：\n"
                    + "· 请对方改用 ZIP 重新打包（推荐）；\n"
                    + "· 在 WinRAR 中把「压缩文件格式」改为 RAR4 后重新压缩；\n"
                    + "· 或先用其它解压工具（如 ZArchiver）在手机上解开该文件。";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Extracts a RAR archive to the specified output directory.
     *
     * <p>Zip Slip protection is applied: an entry whose canonical path does not
     * start with the canonical path of {@code outputDir} is skipped and does not
     * abort the remaining extraction.
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
            postError(callback, "压缩包不存在："
                    + (rarFile != null ? rarFile.getPath() : "null"));
            return;
        }
        if (rarFile.isDirectory()) {
            postError(callback, "所选路径是一个文件夹，不是压缩包：\n" + rarFile.getPath());
            return;
        }
        if (outputDir == null) {
            postError(callback, "输出目录不能为空。");
            return;
        }
        // Reject RAR5 before doing any work.
        if (ArchiveFormat.isUnsupportedRar5(rarFile)) {
            postError(callback, RAR5_UNSUPPORTED_MESSAGE);
            return;
        }

        executor.execute(() -> {
            Archive archive = null;
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
                archive = usePassword ? new Archive(rarFile, password) : new Archive(rarFile);

                if (archive.isEncrypted() && !usePassword) {
                    postError(callback, "该压缩包已加密，请输入密码后重试。");
                    return;
                }

                List<FileHeader> headers = archive.getFileHeaders();
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
                    String entryName = ArchiveIO.normalizeEntryName(resolveEntryName(header));
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

                        try (InputStream in = archive.getInputStream(header);
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

            } catch (UnsupportedRarV5Exception e) {
                postError(callback, RAR5_UNSUPPORTED_MESSAGE);
            } catch (UnsupportedRarEncryptedException e) {
                postError(callback, "该 RAR 使用了当前组件不支持的加密方式（加密头 / -hp），无法解压。\n\n"
                        + "建议先用其它工具解除加密，或改用 ZIP 重新打包。");
            } catch (CorruptHeaderException e) {
                postError(callback, "RAR 文件头损坏，文件可能不完整或已损坏。\n文件："
                        + rarFile.getName() + "（" + rarFile.length() + " 字节）\n\n"
                        + ArchiveIO.describe(e));
            } catch (CrcErrorException e) {
                postError(callback, "解压校验失败（CRC 错误），文件内容可能已损坏。\n条目："
                        + describeEntry(currentEntry) + "\n\n" + ArchiveIO.describe(e));
            } catch (RarException e) {
                postError(callback, "RAR 解压失败。\n条目：" + describeEntry(currentEntry)
                        + "\n\n" + ArchiveIO.describe(e));
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
            } finally {
                if (archive != null) {
                    try {
                        archive.close();
                    } catch (IOException ignored) {
                        // Nothing useful to do if closing fails.
                    }
                }
            }
        });
    }

    /**
     * Returns {@code true} if the RAR archive is password-protected.
     *
     * <p>This is a synchronous check and may be called from any thread.
     * RAR5 files always report {@code false} — junrar cannot read them at all,
     * which is surfaced separately by {@link ArchiveFormat#isUnsupportedRar5(File)}.
     *
     * @param rarFile The RAR file to inspect.
     * @return {@code true} if encrypted, {@code false} otherwise or on error.
     */
    public boolean isEncrypted(File rarFile) {
        if (rarFile == null || !rarFile.exists() || ArchiveFormat.isUnsupportedRar5(rarFile)) {
            return false;
        }
        Archive archive = null;
        try {
            archive = new Archive(rarFile);
            return archive.isEncrypted();
        } catch (Throwable e) {
            return false;
        } finally {
            if (archive != null) {
                try {
                    archive.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the entry name, falling back to the ASCII/Unicode variants when the
     * primary accessor yields nothing.
     */
    private static String resolveEntryName(FileHeader header) {
        String name = header.getFileName();
        if (name == null || name.trim().isEmpty()) {
            name = header.isUnicode() ? header.getFileNameW() : header.getFileNameString();
        }
        return name == null ? "" : name;
    }

    private static String describeEntry(String entryName) {
        return (entryName == null || entryName.isEmpty()) ? "（未知）" : entryName;
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
