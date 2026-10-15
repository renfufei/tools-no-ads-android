package com.tools.cnc.core.qrcode;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Reader;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.PlanarYUVLuminanceSource;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * QR code decoder — pure engine, no Android UI dependencies.
 * Supports decoding from Bitmap, from a content Uri (with EXIF rotation), and
 * from camera YUV luminance frames. All methods are blocking — call from a
 * background thread.
 */
public final class QRCodeDecoder {

    private static final int MAX_DECODE_DIMENSION = 1280;

    private QRCodeDecoder() {
    }

    /** Decode a QR code from a bitmap. Returns the text, or throws NotFoundException. */
    public static String decode(Bitmap src) throws NotFoundException {
        Bitmap bitmap = scaleDown(src, MAX_DECODE_DIMENSION);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        return decodeWithRotations(new RGBLuminanceSource(width, height, pixels));
    }

    /** Decode from a camera Y plane luminance buffer (already extracted). */
    public static String decodeLuminance(byte[] luminance, int width, int height) throws NotFoundException {
        return decodeWithRotations(
                new PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false));
    }

    /**
     * Decode from a content Uri. Handles downsampling of large images and EXIF
     * orientation. Throws NotFoundException when no QR code is detected.
     */
    public static String decodeUri(android.content.Context context, Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, bounds);
        }

        int sampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DECODE_DIMENSION) {
            sampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        Bitmap bitmap;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(is, null, options);
        }
        if (bitmap == null) {
            throw NotFoundException.getNotFoundInstance();
        }
        bitmap = applyExifRotation(context, uri, bitmap);
        return decode(bitmap);
    }

    private static Bitmap applyExifRotation(android.content.Context context, Uri uri, Bitmap bitmap)
            throws Exception {
        int orientation;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            ExifInterface exif = new ExifInterface(is);
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        }
        int degrees;
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                degrees = 90;
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                degrees = 180;
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                degrees = 270;
                break;
            default:
                return bitmap;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private static Bitmap scaleDown(Bitmap bitmap, int maxDimension) {
        int maxSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (maxSide <= maxDimension) {
            return bitmap;
        }
        float scale = (float) maxDimension / maxSide;
        return Bitmap.createScaledBitmap(bitmap,
                Math.max(1, Math.round(bitmap.getWidth() * scale)),
                Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
    }

    /**
     * Try decoding with 4 rotations x 2 binarizers — covers camera orientations
     * and low-contrast images.
     */
    private static String decodeWithRotations(LuminanceSource source) throws NotFoundException {
        MultiFormatReader reader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        reader.setHints(hints);

        LuminanceSource current = source;
        NotFoundException last = null;
        for (int rotation = 0; rotation < 4; rotation++) {
            try {
                return decodeOnce(reader, current).getText();
            } catch (NotFoundException e) {
                last = e;
            }
            current = current.rotateCounterClockwise();
        }
        throw last;
    }

    private static Result decodeOnce(Reader reader, LuminanceSource source) throws NotFoundException {
        try {
            return reader.decode(new BinaryBitmap(new HybridBinarizer(source)));
        } catch (NotFoundException e) {
            // Retry with the global histogram binarizer, better for large images.
            try {
                return reader.decode(new BinaryBitmap(new GlobalHistogramBinarizer(source)));
            } catch (NotFoundException e2) {
                throw e2;
            } catch (Exception ignored) {
                throw e; // checksum/format failures count as "not found"
            }
        } catch (Exception e) {
            throw NotFoundException.getNotFoundInstance();
        } finally {
            reader.reset();
        }
    }
}
