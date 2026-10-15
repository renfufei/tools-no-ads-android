package com.tools.cnc.core.image;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Compresses and/or scales a batch of image files.
 *
 * <p>Compression quality (1–100) applies to JPEG and WebP outputs.
 * Scaling is expressed as a percentage of the original dimensions (10–100).
 * Processing runs on a fixed thread pool of 4 workers; all {@link ImageCallback}
 * methods are posted back to the main thread.</p>
 */
public class ImageCompressor {

    private static final int THREAD_COUNT = 4;

    // Maximum dimension used when pre-scaling large images via inSampleSize
    private static final int MAX_PRE_SCALE_DIMENSION = 4096;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Compresses and scales a list of image files, writing results to {@code outputDir}.
     *
     * @param inputFiles   source image files (JPEG, PNG, WebP, BMP, etc.)
     * @param outputDir    directory where compressed files are written; created if absent
     * @param quality      JPEG/WebP quality, 1–100 (ignored for lossless PNG)
     * @param scalePercent percentage of original dimensions to retain, 10–100
     * @param callback     receives progress, completion, or error events on the main thread
     */
    public void compress(
            List<File> inputFiles,
            File outputDir,
            int quality,
            int scalePercent,
            ImageCallback callback) {

        if (inputFiles == null || inputFiles.isEmpty()) {
            postError(callback, "No input files provided.");
            return;
        }
        if (outputDir == null) {
            postError(callback, "Output directory must not be null.");
            return;
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            postError(callback, "Cannot create output directory: " + outputDir.getAbsolutePath());
            return;
        }

        // Clamp parameters to valid ranges
        final int safeQuality = Math.max(1, Math.min(100, quality));
        final int safeScale = Math.max(10, Math.min(100, scalePercent));
        final int total = inputFiles.size();

        // Thread-safe result accumulator (same order as input)
        final List<File> outputFiles = new ArrayList<>(Collections.nCopies(total, null));
        final AtomicInteger completed = new AtomicInteger(0);
        final AtomicReference<String> firstError = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(total);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < total; i++) {
            final int index = i;
            final File inputFile = inputFiles.get(i);

            executor.submit(() -> {
                try {
                    File outFile = compressSingle(inputFile, outputDir, safeQuality, safeScale);
                    synchronized (outputFiles) {
                        outputFiles.set(index, outFile);
                    }
                } catch (OutOfMemoryError oom) {
                    firstError.compareAndSet(null,
                            "Out of memory processing: " + inputFile.getName());
                } catch (Exception e) {
                    firstError.compareAndSet(null,
                            "Error processing " + inputFile.getName() + ": " + e.getMessage());
                } finally {
                    int done = completed.incrementAndGet();
                    postProgress(callback, done, total, inputFile.getName());
                    latch.countDown();
                }
            });
        }

        // Shutdown executor and wait for all tasks in a dedicated thread to avoid
        // blocking the calling thread (which may be the UI thread).
        Thread waiter = new Thread(() -> {
            executor.shutdown();
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                postError(callback, "Processing interrupted.");
                return;
            }

            String error = firstError.get();
            if (error != null) {
                postError(callback, error);
                return;
            }

            // Filter out any nulls that may have resulted from skipped files
            List<File> results = new ArrayList<>();
            synchronized (outputFiles) {
                for (File f : outputFiles) {
                    if (f != null) results.add(f);
                }
            }
            postComplete(callback, results);
        });
        waiter.setDaemon(true);
        waiter.start();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Compresses and scales a single image file.
     *
     * @return the written output file
     * @throws IOException      on I/O failure
     * @throws OutOfMemoryError if the bitmap cannot fit in heap memory
     */
    private File compressSingle(File inputFile, File outputDir, int quality, int scalePercent)
            throws IOException {

        // --- Step 1: Decode dimensions only to decide inSampleSize ---
        BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
        boundsOpts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(inputFile.getAbsolutePath(), boundsOpts);

        int origWidth = boundsOpts.outWidth;
        int origHeight = boundsOpts.outHeight;

        if (origWidth <= 0 || origHeight <= 0) {
            throw new IOException("Cannot decode image: " + inputFile.getName());
        }

        // --- Step 2: Compute inSampleSize to stay below MAX_PRE_SCALE_DIMENSION ---
        int inSampleSize = 1;
        int halfWidth = origWidth;
        int halfHeight = origHeight;
        while (halfWidth / 2 >= MAX_PRE_SCALE_DIMENSION
                || halfHeight / 2 >= MAX_PRE_SCALE_DIMENSION) {
            halfWidth /= 2;
            halfHeight /= 2;
            inSampleSize *= 2;
        }

        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = inSampleSize;
        decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap source = BitmapFactory.decodeFile(inputFile.getAbsolutePath(), decodeOpts);
        if (source == null) {
            throw new IOException("BitmapFactory returned null for: " + inputFile.getName());
        }

        // --- Step 3: Apply user-requested scale ---
        Bitmap scaled = source;
        if (scalePercent < 100) {
            int newWidth = Math.max(1, source.getWidth() * scalePercent / 100);
            int newHeight = Math.max(1, source.getHeight() * scalePercent / 100);
            scaled = Bitmap.createScaledBitmap(source, newWidth, newHeight, true);
            if (scaled != source) {
                source.recycle();
            }
        }

        // --- Step 4: Determine output format and compress ---
        String name = inputFile.getName();
        String ext = extension(name).toLowerCase(Locale.ROOT);
        Bitmap.CompressFormat format;
        String outExt;
        switch (ext) {
            case "png":
                format = Bitmap.CompressFormat.PNG;
                outExt = "png";
                break;
            case "webp":
                format = Bitmap.CompressFormat.WEBP;
                outExt = "webp";
                break;
            default:
                format = Bitmap.CompressFormat.JPEG;
                outExt = "jpg";
                break;
        }

        String baseName = baseName(name);
        File outFile = new File(outputDir, baseName + "_compressed." + outExt);

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            boolean ok = scaled.compress(format, quality, fos);
            if (!ok) {
                throw new IOException("Bitmap.compress() returned false for: " + name);
            }
        } finally {
            scaled.recycle();
        }

        return outFile;
    }

    // -------------------------------------------------------------------------
    // Callback posting helpers
    // -------------------------------------------------------------------------

    private void postProgress(ImageCallback cb, int current, int total, String file) {
        if (cb == null) return;
        mainHandler.post(() -> cb.onProgress(current, total, file));
    }

    private void postComplete(ImageCallback cb, List<File> files) {
        if (cb == null) return;
        mainHandler.post(() -> cb.onComplete(files));
    }

    private void postError(ImageCallback cb, String message) {
        if (cb == null) return;
        mainHandler.post(() -> cb.onError(message));
    }

    // -------------------------------------------------------------------------
    // Filename utilities
    // -------------------------------------------------------------------------

    /** Returns the extension of {@code filename}, or empty string if none. */
    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot >= 0 && dot < filename.length() - 1) ? filename.substring(dot + 1) : "";
    }

    /** Returns the base name (without extension) of {@code filename}. */
    private static String baseName(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0) ? filename.substring(0, dot) : filename;
    }
}
