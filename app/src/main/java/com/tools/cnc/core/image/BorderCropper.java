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
 * Detects and removes solid dark borders from images, with support for ignoring
 * a configurable band along the top and/or bottom edge.
 *
 * <h3>Why an "ignore band" is needed</h3>
 * A phone screenshot usually carries an Android status bar along the top and a
 * navigation / gesture bar along the bottom. Neither one is a black border -
 * they contain a clock, battery and navigation icons - so a border detector
 * alone will never remove them. They are instead expressed as
 * {@link Options#ignoreTop} / {@link Options#ignoreBottom}: those rows are
 * excluded from border detection <em>and</em> are always cropped away.
 *
 * <h3>Detection algorithm</h3>
 * <ol>
 *   <li>Start the scan at {@code ignoreTop} and stop it at
 *       {@code height - ignoreBottom}, so the status bar can never influence the
 *       detected rectangle - in particular it can never widen the left/right
 *       border, which a full-width status bar would otherwise do.</li>
 *   <li>A row (or column) counts as border when at most
 *       {@link Options#noiseRatio} of its pixels are non-border coloured. A
 *       pixel is border coloured when R, G and B are all below
 *       {@link Options#threshold}.</li>
 *   <li>The innermost non-border row/column indices form the crop rectangle.</li>
 * </ol>
 *
 * <p>The tolerance in step 2 matters: requiring <em>every</em> pixel of a row to
 * be pure black - the naive approach - fails on any real screenshot, because
 * JPEG artefacts, rounded corners and anti-aliasing always leave a few stray
 * pixels behind.</p>
 *
 * <p>All bitmap reads use bulk {@link Bitmap#getPixels} calls rather than
 * per-pixel {@link Bitmap#getPixel(int, int)}.</p>
 */
public class BorderCropper {

    private static final int THREAD_COUNT = 4;

    /** Colour distance (|dR| + |dG| + |dB|) above which two pixels count as different. */
    private static final int EDGE_COLOR_DELTA = 45;
    /** Share of a row that must change for the row to count as a horizontal boundary. */
    private static final float EDGE_CHANGE_RATIO = 0.6f;
    /** Assumed status / navigation bar height when nothing can be measured (share of height). */
    private static final float DEFAULT_INSET_RATIO = 0.03f;
    /** A solid dark band at least this thick is assumed to already cover the status bar. */
    private static final float BAND_SWALLOW_RATIO = 0.015f;
    /** How far past the solid band the auto-detector looks for the status bar boundary. */
    private static final float EDGE_SEARCH_RATIO = 0.06f;
    /** Hard upper bound for the automatic inset estimate. */
    private static final float MAX_INSET_RATIO = 0.12f;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /** Border detection and ignore-band parameters. */
    public static class Options {

        /** A pixel is border coloured when R, G and B are all below this value (0-255). */
        public int threshold = 30;

        /** Share of pixels in a row/column that may break the border colour (0-1). */
        public float noiseRatio = 0.01f;

        /** Rows ignored at the top - excluded from detection and always cropped away. */
        public int ignoreTop = 0;

        /** Rows ignored at the bottom - excluded from detection and always cropped away. */
        public int ignoreBottom = 0;

        public Options() {
        }

        public Options(int threshold, float noiseRatio, int ignoreTop, int ignoreBottom) {
            this.threshold = threshold;
            this.noiseRatio = noiseRatio;
            this.ignoreTop = ignoreTop;
            this.ignoreBottom = ignoreBottom;
        }

        public Options copy() {
            return new Options(threshold, noiseRatio, ignoreTop, ignoreBottom);
        }

        /**
         * Returns a copy whose ignore insets are multiplied by {@code factor}.
         *
         * <p>Used to lift insets measured on a down-scaled preview bitmap back to
         * original-resolution pixels before the final crop.</p>
         */
        public Options scaledInsets(float factor) {
            Options scaled = copy();
            scaled.ignoreTop = Math.round(ignoreTop * factor);
            scaled.ignoreBottom = Math.round(ignoreBottom * factor);
            return scaled;
        }
    }

    // -------------------------------------------------------------------------
    // Geometry helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the pixel region that border detection is allowed to look at:
     * the full width, minus the ignore bands at the top and bottom.
     *
     * <p>{@code rect.bottom} is exclusive, matching {@link Rect} conventions used
     * by {@link Bitmap#getPixels}.</p>
     */
    public static Rect scanRegion(int width, int height, Options options) {
        int top = 0;
        int bottom = height;
        if (options != null) {
            top = clamp(options.ignoreTop, 0, height);
            bottom = height - clamp(options.ignoreBottom, 0, height - top);
        }
        if (bottom < top) {
            bottom = top;
        }
        return new Rect(0, top, width, bottom);
    }

    // -------------------------------------------------------------------------
    // Border detection
    // -------------------------------------------------------------------------

    /**
     * Scans {@code bitmap} and returns the tightest {@link Rect} of content,
     * honouring the caller's ignore bands.
     *
     * <p>{@code rect.top} is always at least {@code options.ignoreTop}, and
     * {@code rect.bottom} is always at most {@code height - options.ignoreBottom}.</p>
     *
     * @param bitmap  the source bitmap; must not be recycled
     * @param options detection parameters; {@code null} falls back to defaults
     * @return the content rectangle, or {@code null} when the scan region is
     *         border coloured all the way through (callers should then fall back
     *         to {@link #scanRegion(int, int, Options)})
     */
    public Rect detectContentBounds(Bitmap bitmap, Options options) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }
        final Options opts = (options == null) ? new Options() : options;

        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }

        final Rect scan = scanRegion(width, height, opts);
        final int yStart = scan.top;
        final int yEnd = scan.bottom - 1;
        if (yEnd < yStart || scan.right <= scan.left) {
            return null;
        }

        final int[] rowPixels = new int[width];
        final int[] colPixels = new int[yEnd - yStart + 1];

        // --- top: first row inside the scan region that is not a border row ---
        int top = -1;
        for (int y = yStart; y <= yEnd; y++) {
            bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1);
            if (!isBorderLine(rowPixels, width, opts)) {
                top = y;
                break;
            }
        }
        if (top < 0) {
            // The whole scan region is border coloured - nothing to keep.
            return null;
        }

        // --- bottom: last row inside the scan region that is not a border row ---
        int bottom = top;
        for (int y = yEnd; y >= top; y--) {
            bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1);
            if (!isBorderLine(rowPixels, width, opts)) {
                bottom = y;
                break;
            }
        }

        // --- left / right, restricted to the rows we just kept ---
        final int colCount = bottom - top + 1;

        int left = 0;
        for (int x = 0; x < width; x++) {
            bitmap.getPixels(colPixels, 0, 1, x, top, 1, colCount);
            if (!isBorderLine(colPixels, colCount, opts)) {
                left = x;
                break;
            }
        }

        int right = width - 1;
        for (int x = width - 1; x >= left; x--) {
            bitmap.getPixels(colPixels, 0, 1, x, top, 1, colCount);
            if (!isBorderLine(colPixels, colCount, opts)) {
                right = x;
                break;
            }
        }

        return new Rect(left, top, right + 1, bottom + 1);
    }

    /**
     * Backwards-compatible overload without ignore bands.
     *
     * @param threshold border detection threshold (0-255)
     */
    public Rect detectContentBounds(Bitmap bitmap, int threshold) {
        return detectContentBounds(bitmap, new Options(threshold, 0.01f, 0, 0));
    }

    // -------------------------------------------------------------------------
    // Automatic status / navigation bar estimation
    // -------------------------------------------------------------------------

    /**
     * Estimates how many pixels at the top and at the bottom of {@code bitmap}
     * belong to the system status bar / navigation bar.
     *
     * <p>The estimate is a <em>suggestion</em> that the UI pre-fills into the two
     * ignore sliders; the user is expected to verify it against the live preview.</p>
     *
     * <p>Heuristic per edge:</p>
     * <ol>
     *   <li>Measure the solid (dark) band running along that edge. Screenshots
     *       pasted onto a black background start with such a band.</li>
     *   <li>A band at least {@value #BAND_SWALLOW_RATIO} of the image height is
     *       assumed to have swallowed the status bar already, so it is returned
     *       as-is.</li>
     *   <li>Otherwise look for the first strong horizontal edge just past the
     *       band: that edge is where the status bar meets the app content.</li>
     *   <li>If nothing can be measured, fall back to a typical bar height of
     *       {@value #DEFAULT_INSET_RATIO} of the image height.</li>
     * </ol>
     *
     * <p>Note that a fully edge-to-edge screenshot - white status bar text drawn
     * straight onto white app content - carries no pixel-level signal at all, so
     * only step 4 applies there.</p>
     *
     * @param bitmap source bitmap, must not be recycled
     * @param options detection parameters used to recognise the solid band
     * @return a two element array {@code {topInsetPx, bottomInsetPx}}
     */
    public static int[] estimateInsets(Bitmap bitmap, Options options) {
        if (bitmap == null || bitmap.isRecycled()) {
            return new int[]{0, 0};
        }
        final Options opts = (options == null) ? new Options() : options;
        final int height = bitmap.getHeight();
        final int maxPx = Math.max(1, Math.round(height * MAX_INSET_RATIO));

        return new int[]{
                estimateEdge(bitmap, opts, true, maxPx),
                estimateEdge(bitmap, opts, false, maxPx)
        };
    }

    private static int estimateEdge(Bitmap bitmap, Options opts, boolean fromTop, int maxPx) {
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        final int limit = Math.min(maxPx, height - 1);
        if (limit <= 1 || width <= 1) {
            return 0;
        }

        final int[] rowPixels = new int[width];
        int[] prev = new int[width];
        int[] cur = new int[width];

        // --- Step 1: thickness of the solid dark band hugging this edge ---
        int band = 0;
        for (int i = 0; i < limit; i++) {
            int y = fromTop ? i : height - 1 - i;
            bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1);
            if (!isBorderLine(rowPixels, width, opts)) {
                break;
            }
            band = i + 1;
        }

        // --- Step 2: a thick solid band already covers the status bar ---
        int swallowThreshold = Math.max(6, Math.round(height * BAND_SWALLOW_RATIO));
        if (band >= swallowThreshold) {
            return band;
        }

        // --- Step 3: first strong horizontal edge past the band ---
        int searchEnd = Math.min(limit,
                band + Math.max(1, Math.round(height * EDGE_SEARCH_RATIO)));
        int need = (int) Math.ceil(width * EDGE_CHANGE_RATIO);

        if (band < searchEnd) {
            int prevY = fromTop ? band : height - 1 - band;
            bitmap.getPixels(prev, 0, width, 0, prevY, width, 1);

            for (int i = band + 1; i <= searchEnd; i++) {
                int y = fromTop ? i : height - 1 - i;
                if (y < 0 || y >= height) {
                    break;
                }
                bitmap.getPixels(cur, 0, width, 0, y, width, 1);

                int changed = 0;
                for (int x = 0; x < width; x++) {
                    if (colorDelta(prev[x], cur[x]) > EDGE_COLOR_DELTA) {
                        changed++;
                        if (changed >= need) {
                            return i;
                        }
                    }
                }

                int[] swap = prev;
                prev = cur;
                cur = swap;
            }
        }

        // --- Step 4: nothing measurable, assume a typical bar height ---
        int fallback = band + Math.round(height * DEFAULT_INSET_RATIO);
        return clamp(fallback, band, limit);
    }

    // -------------------------------------------------------------------------
    // Cropping
    // -------------------------------------------------------------------------

    /**
     * Loads {@code inputFile}, removes its border (and ignore bands) and writes
     * the result to {@code outputFile}.
     *
     * <p>Detection runs at full resolution here, independent of any down-scaled
     * preview the caller may have used to lay out its UI.</p>
     *
     * @param inputFile  source image file
     * @param outputFile destination file (created or overwritten)
     * @param options    detection parameters
     * @throws IOException      on any I/O or decode failure
     * @throws OutOfMemoryError if the bitmap cannot fit in heap memory
     */
    public void crop(File inputFile, File outputFile, Options options)
            throws IOException {

        final Options opts = (options == null) ? new Options() : options;

        Bitmap source = decodeBitmap(inputFile);
        if (source == null) {
            throw new IOException("Cannot decode image: " + inputFile.getName());
        }

        Bitmap result = null;
        try {
            Rect bounds = detectContentBounds(source, opts);
            if (bounds == null) {
                // Everything inside the scan region looks like border. Still honour
                // the ignore bands so that an over-dark image keeps its status bar
                // cropped away.
                bounds = scanRegion(source.getWidth(), source.getHeight(), opts);
            }

            bounds.left = clamp(bounds.left, 0, source.getWidth());
            bounds.top = clamp(bounds.top, 0, source.getHeight());
            bounds.right = clamp(bounds.right, bounds.left, source.getWidth());
            bounds.bottom = clamp(bounds.bottom, bounds.top, source.getHeight());

            if (bounds.width() <= 0 || bounds.height() <= 0) {
                throw new IOException("Crop rectangle is empty for: " + inputFile.getName());
            }

            result = Bitmap.createBitmap(
                    source,
                    bounds.left, bounds.top,
                    bounds.width(), bounds.height());

            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            Bitmap.CompressFormat fmt = compressFormatFor(outputFile.getName());
            int quality = (fmt == Bitmap.CompressFormat.PNG) ? 100 : 95;

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                boolean ok = result.compress(fmt, quality, fos);
                if (!ok) {
                    throw new IOException("Bitmap.compress() failed for: " + outputFile.getName());
                }
            }
        } finally {
            source.recycle();
            if (result != null && result != source) {
                result.recycle();
            }
        }
    }

    /** Backwards-compatible overload without ignore bands. */
    public void crop(File inputFile, File outputFile, int threshold) throws IOException {
        crop(inputFile, outputFile, new Options(threshold, 0.01f, 0, 0));
    }

    /**
     * Crops borders from a batch of files, writing results to {@code outputDir}.
     *
     * <p>Processing is parallelised across up to {@value #THREAD_COUNT} threads.
     * Progress and completion events are posted to the main thread via
     * {@link ImageCallback}.</p>
     *
     * @param inputFiles source image files
     * @param outputDir  directory for cropped output files; created if absent
     * @param options    detection parameters
     * @param callback   receives progress, completion, or error events on the main thread
     */
    public void cropBatch(List<File> inputFiles, File outputDir, Options options,
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

        final Options opts = (options == null) ? new Options() : options;
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
                    String base = baseName(inputFile.getName());
                    String ext = extension(inputFile.getName());
                    String outName = base + "_cropped." + (ext.isEmpty() ? "jpg" : ext);
                    outFile = new File(outputDir, outName);
                    crop(inputFile, outFile, opts);
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

                    postProgress(callback, done, total, inputFile.getName());

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

    /** Backwards-compatible overload without ignore bands. */
    public void cropBatch(List<File> inputFiles, File outputDir, int threshold,
                          ImageCallback callback) {
        cropBatch(inputFiles, outputDir, new Options(threshold, 0.01f, 0, 0), callback);
    }

    // -------------------------------------------------------------------------
    // Pixel predicates
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code pixels} reads as a border line: at most
     * {@link Options#noiseRatio} of the samples break the border colour.
     */
    private static boolean isBorderLine(int[] pixels, int count, Options opts) {
        int allowed = (int) (count * Math.max(0f, opts.noiseRatio));
        int broken = 0;
        for (int i = 0; i < count; i++) {
            if (!isBorderPixel(pixels[i], opts.threshold)) {
                broken++;
                if (broken > allowed) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Returns {@code true} when every RGB channel of {@code pixel} is below {@code threshold}. */
    private static boolean isBorderPixel(int pixel, int threshold) {
        return Color.red(pixel) < threshold
                && Color.green(pixel) < threshold
                && Color.blue(pixel) < threshold;
    }

    /** Sum of absolute per-channel differences between two ARGB pixels. */
    private static int colorDelta(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b))
                + Math.abs(Color.green(a) - Color.green(b))
                + Math.abs(Color.blue(a) - Color.blue(b));
    }

    private static int clamp(int value, int min, int max) {
        if (min > max) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    // -------------------------------------------------------------------------
    // Decoding and file helpers
    // -------------------------------------------------------------------------

    /**
     * Decodes a {@link Bitmap} at full resolution.
     *
     * <p>Border detection needs every pixel, so {@code inSampleSize} is left at 1.
     * Very large images may still trigger an {@link OutOfMemoryError}, which the
     * caller is expected to catch.</p>
     */
    private static Bitmap decodeBitmap(File file) {
        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        decodeOpts.inSampleSize = 1;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), decodeOpts);
    }

    private static Bitmap.CompressFormat compressFormatFor(String filename) {
        switch (extension(filename).toLowerCase(Locale.ROOT)) {
            case "png":
                return Bitmap.CompressFormat.PNG;
            case "webp":
                return Bitmap.CompressFormat.WEBP;
            default:
                return Bitmap.CompressFormat.JPEG;
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
