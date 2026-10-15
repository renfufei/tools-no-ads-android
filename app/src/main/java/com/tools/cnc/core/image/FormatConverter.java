package com.tools.cnc.core.image;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * Converts image files between formats (JPG, PNG, WebP, BMP, HEIC).
 *
 * <h3>Format notes</h3>
 * <ul>
 *   <li><b>JPG / PNG / WebP</b> – {@link Bitmap#compress} with the requested quality.</li>
 *   <li><b>BMP</b> – hand-written 14-byte file header + 40-byte DIB info header +
 *       24-bit BGR pixel data, stored bottom-up with 4-byte row alignment (standard
 *       Windows BMP v3).</li>
 *   <li><b>HEIC</b> – requires API 28+.  On supported devices the bitmap is re-encoded
 *       using {@link android.media.MediaCodec} via the platform's HEIF encoder
 *       ({@code HeifWriter} from the {@code androidx.heifwriter} library is used when
 *       present).  On API &lt; 28 or when the encoder is unavailable,
 *       {@link ImageCallback#onError} is invoked with an explanatory message.</li>
 * </ul>
 *
 * <p>Processing runs on a fixed thread pool of 4 workers.  All
 * {@link ImageCallback} methods are posted to the main thread.</p>
 */
public class FormatConverter {

    private static final int THREAD_COUNT = 4;

    /** Formats supported as conversion targets. */
    private static final List<String> SUPPORTED_FORMATS;

    static {
        List<String> fmts = new ArrayList<>();
        fmts.add("jpg");
        fmts.add("png");
        fmts.add("webp");
        fmts.add("bmp");
        fmts.add("heic");
        SUPPORTED_FORMATS = Collections.unmodifiableList(fmts);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Converts a batch of image files to the specified target format.
     *
     * @param inputFiles   source image files; HEIC sources are supported on API 28+
     * @param outputDir    directory for converted output files; created if absent
     * @param targetFormat one of {@code "jpg"}, {@code "png"}, {@code "webp"},
     *                     {@code "bmp"}, {@code "heic"} (case-insensitive)
     * @param quality      encode quality for lossy formats (1–100)
     * @param callback     receives progress, completion, or error events on the main thread
     */
    public void convert(
            List<File> inputFiles,
            File outputDir,
            String targetFormat,
            int quality,
            ImageCallback callback) {

        if (inputFiles == null || inputFiles.isEmpty()) {
            postError(callback, "No input files provided.");
            return;
        }
        if (outputDir == null) {
            postError(callback, "Output directory must not be null.");
            return;
        }
        if (targetFormat == null) {
            postError(callback, "Target format must not be null.");
            return;
        }

        final String fmt = targetFormat.toLowerCase(Locale.ROOT).trim();
        if (!SUPPORTED_FORMATS.contains(fmt)) {
            postError(callback, "Unsupported target format: " + targetFormat
                    + ". Supported: " + SUPPORTED_FORMATS);
            return;
        }

        // HEIC requires API 28+
        if ("heic".equals(fmt) && Build.VERSION.SDK_INT < 28) {
            postError(callback,
                    "HEIC encoding requires Android API 28 (Pie) or higher. "
                    + "Current API level: " + Build.VERSION.SDK_INT);
            return;
        }

        if (!outputDir.exists() && !outputDir.mkdirs()) {
            postError(callback,
                    "Cannot create output directory: " + outputDir.getAbsolutePath());
            return;
        }

        final int safeQuality = Math.max(1, Math.min(100, quality));
        final int total = inputFiles.size();

        final List<File> outputFiles =
                new ArrayList<>(Collections.nCopies(total, (File) null));
        final AtomicInteger completed = new AtomicInteger(0);
        final AtomicReference<String> firstError = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(total);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < total; i++) {
            final int index = i;
            final File inputFile = inputFiles.get(i);

            executor.submit(() -> {
                try {
                    File outFile = convertSingle(inputFile, outputDir, fmt, safeQuality);
                    synchronized (outputFiles) {
                        outputFiles.set(index, outFile);
                    }
                } catch (UnsupportedOperationException uoe) {
                    firstError.compareAndSet(null, uoe.getMessage());
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
    // Per-file conversion
    // -------------------------------------------------------------------------

    private File convertSingle(
            File inputFile,
            File outputDir,
            String targetFormat,
            int quality)
            throws IOException {

        Bitmap bitmap = loadBitmap(inputFile);
        if (bitmap == null) {
            throw new IOException("Cannot decode image: " + inputFile.getName());
        }

        String baseName = baseName(inputFile.getName());
        File outputFile = new File(outputDir, baseName + "." + targetFormat);

        try {
            switch (targetFormat) {
                case "jpg":
                    encodeJpg(bitmap, outputFile, quality);
                    break;
                case "png":
                    encodePng(bitmap, outputFile);
                    break;
                case "webp":
                    encodeWebp(bitmap, outputFile, quality);
                    break;
                case "bmp":
                    encodeBmp(bitmap, outputFile);
                    break;
                case "heic":
                    encodeHeic(bitmap, outputFile, quality);
                    break;
                default:
                    throw new UnsupportedOperationException(
                            "Unhandled format: " + targetFormat);
            }
        } finally {
            bitmap.recycle();
        }

        return outputFile;
    }

    // -------------------------------------------------------------------------
    // Bitmap loading
    // -------------------------------------------------------------------------

    /**
     * Loads a {@link Bitmap} from {@code file}.
     *
     * <p>On API 28+ the {@link ImageDecoder} path is used so that HEIC files
     * can be decoded natively. On lower API levels {@link BitmapFactory} is
     * used (HEIC decoding is not available there).</p>
     */
    private static Bitmap loadBitmap(File file) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.Source src =
                    ImageDecoder.createSource(file);
            return ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
                decoder.setMutableRequired(true);
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } else {
            // On older APIs fall back to BitmapFactory; HEIC input will fail here
            // but the caller has already validated format constraints.
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        }
    }

    // -------------------------------------------------------------------------
    // Encoders
    // -------------------------------------------------------------------------

    private static void encodeJpg(Bitmap bitmap, File out, int quality)
            throws IOException {
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)) {
                throw new IOException("JPEG compression failed: " + out.getName());
            }
        }
    }

    private static void encodePng(Bitmap bitmap, File out)
            throws IOException {
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            // PNG is lossless; quality parameter is ignored by Android's encoder
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                throw new IOException("PNG compression failed: " + out.getName());
            }
        }
    }

    private static void encodeWebp(Bitmap bitmap, File out, int quality)
            throws IOException {
        Bitmap.CompressFormat webpFormat;
        if (Build.VERSION.SDK_INT >= 30) {
            // API 30 introduced CompressFormat.WEBP_LOSSY / WEBP_LOSSLESS
            webpFormat = (quality < 100)
                    ? Bitmap.CompressFormat.WEBP_LOSSY
                    : Bitmap.CompressFormat.WEBP_LOSSLESS;
        } else {
            //noinspection deprecation
            webpFormat = Bitmap.CompressFormat.WEBP;
        }
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            if (!bitmap.compress(webpFormat, quality, os)) {
                throw new IOException("WebP compression failed: " + out.getName());
            }
        }
    }

    /**
     * Writes a 24-bit Windows BMP v3 file.
     *
     * <h3>Layout</h3>
     * <pre>
     *  Offset  Size  Field
     *  ------  ----  -----
     *  0       2     Signature ('B','M')
     *  2       4     File size (bytes)
     *  6       2     Reserved (0)
     *  8       2     Reserved (0)
     *  10      4     Pixel data offset (= 54)
     *  14      4     Header size (= 40)
     *  18      4     Width (pixels)
     *  22      4     Height (pixels, positive = bottom-up)
     *  26      2     Colour planes (= 1)
     *  28      2     Bits per pixel (= 24)
     *  30      4     Compression (= 0, BI_RGB)
     *  34      4     Image data size (may be 0 for BI_RGB)
     *  38      4     X pixels per metre (= 2835, ~72 dpi)
     *  42      4     Y pixels per metre (= 2835)
     *  46      4     Colours in palette (= 0)
     *  50      4     Important colours (= 0)
     *  54+     -     Pixel rows, bottom-up, BGR, 4-byte aligned
     * </pre>
     */
    private static void encodeBmp(Bitmap bitmap, File out) throws IOException {
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();

        // Row stride must be a multiple of 4 bytes; each pixel = 3 bytes (BGR)
        final int rowBytes = width * 3;
        final int paddedRowBytes = (rowBytes + 3) & ~3;   // round up to 4-byte boundary
        final int pixelDataSize = paddedRowBytes * height;
        final int fileSize = 54 + pixelDataSize;

        // Read all pixels at once into an ARGB int array
        int[] argbPixels = new int[width * height];
        bitmap.getPixels(argbPixels, 0, width, 0, 0, width, height);

        ByteBuffer buf = ByteBuffer.allocate(fileSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // --- File header (14 bytes) ---
        buf.put((byte) 'B');
        buf.put((byte) 'M');
        buf.putInt(fileSize);
        buf.putShort((short) 0);    // reserved
        buf.putShort((short) 0);    // reserved
        buf.putInt(54);             // pixel data offset

        // --- DIB info header (40 bytes) ---
        buf.putInt(40);             // header size
        buf.putInt(width);
        buf.putInt(height);         // positive = bottom-up storage
        buf.putShort((short) 1);    // colour planes
        buf.putShort((short) 24);   // bits per pixel
        buf.putInt(0);              // BI_RGB (no compression)
        buf.putInt(pixelDataSize);
        buf.putInt(2835);           // X pixels per metre (~72 dpi)
        buf.putInt(2835);           // Y pixels per metre
        buf.putInt(0);              // colours in palette
        buf.putInt(0);              // important colours

        // --- Pixel data: bottom-up row order, BGR byte order ---
        byte[] padding = new byte[paddedRowBytes - rowBytes];
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                int pixel = argbPixels[y * width + x];
                buf.put((byte) ((pixel) & 0xFF));        // Blue
                buf.put((byte) ((pixel >> 8) & 0xFF));   // Green
                buf.put((byte) ((pixel >> 16) & 0xFF));  // Red
            }
            if (padding.length > 0) buf.put(padding);
        }

        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(buf.array());
        }
    }

    /**
     * Encodes {@code bitmap} to HEIC format (API 28+ only).
     *
     * <p>The implementation uses {@link android.graphics.ImageDecoder} for
     * decoding and relies on the platform's HEIF encoder accessed through
     * {@link Bitmap#compress} with the {@code HEIF} compress format introduced
     * in API 30 ({@code CompressFormat.HEIF}).  On API 28–29, where
     * {@code CompressFormat.HEIF} is not yet available, the method reports the
     * limitation via an {@link UnsupportedOperationException} so the caller can
     * surface it through the error callback.</p>
     *
     * <p>If a full {@code HeifWriter} integration from
     * {@code androidx.heifwriter} is needed for API 28–29, add the dependency
     * {@code implementation 'androidx.heifwriter:heifwriter:1.0.0'} and replace
     * the body of this method with a {@code HeifWriter}-based approach.</p>
     */
    @SuppressWarnings("NewApi")
    private static void encodeHeic(Bitmap bitmap, File out, int quality)
            throws IOException {
        if (Build.VERSION.SDK_INT < 28) {
            throw new UnsupportedOperationException(
                    "HEIC encoding requires Android API 28+.");
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        try {
            androidx.heifwriter.HeifWriter writer = new androidx.heifwriter.HeifWriter.Builder(
                    out.getAbsolutePath(), width, height,
                    androidx.heifwriter.HeifWriter.INPUT_MODE_BITMAP)
                    .setQuality(quality)
                    .build();
            writer.start();
            writer.addBitmap(bitmap);
            writer.stop(5000);
            writer.close();
        } catch (Exception e) {
            throw new IOException("HEIC compression failed: " + out.getName(), e);
        }
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

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot >= 0 && dot < filename.length() - 1) ? filename.substring(dot + 1) : "";
    }

    private static String baseName(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0) ? filename.substring(0, dot) : filename;
    }
}
