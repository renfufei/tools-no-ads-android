package com.tools.cnc.util;

import java.io.File;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileUtils {

    private static final String[] SIZE_UNITS = {"B", "KB", "MB", "GB"};

    public static String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        digitGroups = Math.min(digitGroups, SIZE_UNITS.length - 1);
        DecimalFormat df = new DecimalFormat("#,##0.#");
        return df.format(size / Math.pow(1024, digitGroups)) + " " + SIZE_UNITS[digitGroups];
    }

    public static String formatDate(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    public static String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0) return "";
        return filename.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }

    public static String getNameWithoutExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0) return filename;
        return filename.substring(0, lastDot);
    }

    public static boolean isArchive(String filename) {
        String ext = getExtension(filename);
        return ext.equals("zip") || ext.equals("rar");
    }

    public static boolean isImage(String filename) {
        String ext = getExtension(filename);
        return ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png")
                || ext.equals("webp") || ext.equals("bmp") || ext.equals("gif")
                || ext.equals("heic") || ext.equals("heif");
    }

    public static String getMimeType(String extension) {
        switch (extension.toLowerCase(Locale.ROOT)) {
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "gif": return "image/gif";
            case "heic":
            case "heif": return "image/heic";
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            case "rar": return "application/x-rar-compressed";
            default: return "application/octet-stream";
        }
    }

    public static File getUniqueFile(File dir, String baseName, String extension) {
        File file = new File(dir, baseName + "." + extension);
        int counter = 1;
        while (file.exists()) {
            file = new File(dir, baseName + " (" + counter + ")." + extension);
            counter++;
        }
        return file;
    }
}
