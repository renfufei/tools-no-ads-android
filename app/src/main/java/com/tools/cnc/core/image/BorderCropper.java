package com.tools.cnc.core.image;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Detects and removes solid-colour (typically black) borders from images.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Read each row of pixels. A row is considered "border" if <em>every</em>
 *       pixel in that row has all three RGB channels below {@code threshold}.</li>
 *   <li>The same logic is applied to columns from both sides.</li>
 *   <li>The innermost non-border row/column indices form the crop rectangle.</li>
 * </ol>
 *
 * <p>{@link #getPixels(int[], int, int, int, int, int)} is used for efficient
 * bulk pixel retrieval instead of per-pixel {@link Bitmap#getPixel(int, int)}
 * calls.</p>
 */
public class BorderCropper {

    private static final int THREAD_COUNT = 4;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Scans {@code bitmap} and returns the tightest {@link Rect} that excludes
     * all-black border rows and columns.
     *
     * <p>A pixel is considered "black" (border) when every one of its R, G, B
     * channels is strictly less than {@code threshold}.</p>
     *
     * @param bitmap    the source bitmap; must not be recycled
     * @param threshold pixel channel value below which a channel is treated as black (0–255)
     * @return the content bounding rectangle, or the full image rect if no border is found.
     *         Returns {@code null} if the entire image is black.
     */
    public Rect detectContentBounds(Bitmap bitmap, int threshold) {
        if (bitmap == null || bitmap.isRecycled()) return null;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // Row buffer – reused for scanning rows
        int[] rowPixels = new int[width];
        // Column buffer – reused for scanning columns
        int[] colPixels = new int[height];

        // --- Scan from top ---
        int top = 0;
        outer:
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                if (!isBlack(rowPixels[x], threshold)) {
                    top = y;
                    break outer;
                }
            }
            // If we exhaust all rows the image is entirely black
            if (y == height - 1) return null;
        }

        // --- Scan from bottom ---
        int bottom = height - 1;
        outer:
        for (int y = height - 1; y >= top; y--) {
            bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                if (!isBlack(rowPixels[x], threshold)) {
                    bottom = y;
                    break outer;
                }
            }
        }

        // --- Scan from left ---
        int left = 0;
        outer:
        for (int x = 0; x < width; x++) {
            bitmap.getPixels(colPixels, 0, 1, x, top, 1, bottom - top + 1);
            for (int y = 0; y <= bottom - top; y++) {
                if (!isBlack(colPixels[y], threshold)) {
                    left = x;
                    break outer;
                }
            }
        }

        // --- Scan from right ---
        int right = width - 1;
        outer:
        for (int x = width - 1; x >= left; x--) {
            bitmap.getPixels(colPixels, 0, 1, x, top, 1, bottom - top + 1);
            for (int y = 0; y <= bottom - top; y++) {
                if (!isBlack(colPixels[y], threshold)) {
                    right = x;
                    break outer;
                }
            }
        }

        return new Rect(left, top, right + 1, bottom + 1);
    }

    /**
     * Loads {@code inputFile}, detects its border region, crops it and saves
     * the result to {@code outputFile}.
     *
     * <p>If no border is detected the original image is written unchanged.</p>
     *
     * @param inputFile  source image file
     * @param outputFile destination file (created or overwritten)
     * @param threshold  border detection threshold (0–255)
     * @throws IOException      on any I/O or decode failure
     * @throws OutOfMemoryError if the bitmap cannot fit in heap memory
     */
    public void crop(File inputFile, File outputFile, int threshold)
            throws IOException {

        Bitmap source = decodeBitmap(inputFile);
        if (source == null) {
            throw new IOException("Cannot decode image: " + inputFile.getName());
        }

        Bitmap result = null;
        try {
            Rect bounds = detectContentBounds(source, threshold);

            if (bounds == null) {
                // Entire image is black – write as-is
                bounds = new Rect(0, 0, source.getWidth(), source.getHeight());
            }

            // Clamp to bitmap dimensions for safety
            bounds.left   = Math.max(0, bounds.left);
            bounds.top    = Math.max(0, bounds.top);
            bounds.right  = Math.min(source.getWidth(),  bounds.right);
            bounds.bottom = Math.min(source.getHeight(), bounds.bottom);

            if (bounds.width() <= 0 || bounds.height() <= 0) {
                throw new IOException("Crop rectangle is empty for: " + inputFile.getName());
            }

            result = Bitmap.createBitmap(
                    source,
                    bounds.left, bounds.top,
                    bounds.width(), bounds.height());

            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            Bitmap.CompressFormat fmt = compressFormatFor(outputFile.getName());
            int quality = (fmt == Bitmap.CompressFormat.PNG) ? 100 : 95;

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                boolean ok = result.compress(fmt, quality, fos);
                if (!ok) {
                    throw new IOException(
                            "Bitmap.compress() failed for: " + outputFile.getName());
                }
            }
        } finally {
            source.recycle();
            if (result != null && result != source) result.recycle();
        }
    }

    /**
     * Crops borders from a batch of files, writing results to {@code outputDir}.
     *
     * <p>Processing is parallelised across up to 4 threads. Progress and
     * completion events are posted to the main thread via {@link ImageCallback}.</p>
     *
     * @param inputFiles source image files
     * @param outputDir  directory for cropped output files; created if absent
     * @param threshold  border detection threshold (0–255)
     * @param callback   receives progress, completion, or error events on the main thread
     */
    public void cropBatch(
            List<File> inputFiles,
            File outputDir,
            int threshold,
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

        final int total = inputFiles.size();
        final List<File> results = new ArrayList<>(total);
        final int[] completed = {0};
        final String[] firstError = {null};
        final Object lock = new Object();

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (File inputFile : inputFiles) {
            executor.submit(() -> {
                File outFile = null;
                String errorMsg = null;

                try {
                    String baseName = baseName(inputFile.getName());
                    String ext = extension(inputFile.getName());
                    String outName = baseName + "_cropped." + (ext.isEmpty() ? "jpg" : ext);
                    outFile = new File(outputDir, outName);
                    crop(inputFile, outFile, threshold);
                } catch (OutOfMemoryError oom) {
                    errorMsg = "Out of memory processing: " + inputFile.getName();
                    outFile = null;
                } catch (IOException e) {
                    errorMsg = "Error processing " + inputFile.getName() + ": " + e.getMessage();
                    outFile = null;
                }

                synchronized (lock) {
                    completed[0]++;
                    int done = completed[0];

                    if (errorMsg != null && firstError[0] == null) {
                        firstError[0] = errorMsg;
                    }
                    if (outFile != null) {
                        results.add(outFile);
                    }

                    final int doneFinal = done;
                    postProgress(callback, doneFinal, total, inputFile.getName());

                    if (done == total) {
                        if (firstError[0] != null) {
                            postError(callback, firstError[0]);
                        } else {
                            postComplete(callback, new ArrayList<>(results));
                        }
                    }
                }
            });
        }

        executor.shutdown();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if every RGB channel of {@code pixel} is below
     * {@code threshold} (i.e. the pixel is "black" for border detection).
     */
    private static boolean isBlack(int pixel, int threshold) {
        return Color.red(pixel) < threshold
                && Color.green(pixel) < threshold
                && Color.blue(pixel) < threshold;
    }

    /**
     * Decodes a {@link Bitmap} from a file, using {@code inSampleSize} to avoid
     * loading excessively large images fully into memory in one shot.
     */
    private static Bitmap decodeBitmap(File file) {
        // Decode bounds first
        BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
        boundsOpts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), boundsOpts);

        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        // Use inSampleSize = 1 (full resolution) because border detection
        // requires examining every pixel accurately. Very large images may still
        // trigger OOM – the caller catches OutOfMemoryError.
        decodeOpts.inSampleSize = 1;

        return BitmapFactory.decodeFile(file.getAbsolutePath(), decodeOpts);
    }

    private static Bitmap.CompressFormat compressFormatFor(String filename) {
        switch (extension(filename).toLowerCase(Locale.ROOT)) {
            case "png":  return Bitmap.CompressFormat.PNG;
            case "webp": return Bitmap.CompressFormat.WEBP;
            default:     return Bitmap.CompressFormat.JPEG;
        }
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot >= 0 && dot < filename.length() - 1) ? filename.substring(dot + 1) : "";
    }

    private static String baseName(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0) ? filename.substring(0, dot) : filename;
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
}
