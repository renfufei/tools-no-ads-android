package com.tools.cnc.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class StorageHelper {

    public static File getDefaultStorageDir() {
        return Environment.getExternalStorageDirectory();
    }

    public static File getOutputDir(Context context) {
        File dir = new File(Environment.getExternalStorageDirectory(), "ToolsCNC");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File getArchiveOutputDir(Context context) {
        File dir = new File(getOutputDir(context), "Archive");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File getImageOutputDir(Context context) {
        if (PermissionHelper.hasStoragePermission(context)) {
            File dir = new File(getOutputDir(context), "Images");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return dir;
        }
        // Fallback to public Downloads/ToolsCNC/Images when no full storage access
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "ToolsCNC/Images");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File getQrCodeOutputDir(Context context) {
        if (PermissionHelper.hasStoragePermission(context)) {
            File dir = new File(getOutputDir(context), "QRCode");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return dir;
        }
        // Fallback to public Downloads/ToolsCNC/QRCode when no full storage access
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "ToolsCNC/QRCode");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File getCompressOutputDir() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "ToolsCNC");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static String getFileName(Context context, Uri uri) {
        String name = null;
        if (uri.getScheme().equals(ContentResolver.SCHEME_CONTENT)) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        name = cursor.getString(idx);
                    }
                }
            }
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        return name;
    }

    public static File copyUriToCache(Context context, Uri uri, String fileName) throws IOException {
        File cacheFile = new File(context.getCacheDir(), fileName);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(cacheFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        return cacheFile;
    }
}
