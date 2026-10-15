package com.tools.cnc.ui.archive;

import java.io.File;
import java.util.Objects;

public class FileItem {
    public enum FileType {
        FOLDER, ZIP, RAR, OTHER
    }

    private final File file;
    private final FileType type;
    private boolean selected;

    public FileItem(File file) {
        this.file = file;
        this.type = determineType(file);
        this.selected = false;
    }

    private static FileType determineType(File file) {
        if (file.isDirectory()) return FileType.FOLDER;
        String name = file.getName().toLowerCase();
        if (name.endsWith(".zip")) return FileType.ZIP;
        if (name.endsWith(".rar")) return FileType.RAR;
        return FileType.OTHER;
    }

    public File getFile() { return file; }
    public String getName() { return file.getName(); }
    public FileType getType() { return type; }
    public long getSize() { return file.length(); }
    public long getLastModified() { return file.lastModified(); }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }

    public boolean isArchive() {
        return type == FileType.ZIP || type == FileType.RAR;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileItem fileItem = (FileItem) o;
        return Objects.equals(file, fileItem.file);
    }

    @Override
    public int hashCode() {
        return Objects.hash(file);
    }
}
