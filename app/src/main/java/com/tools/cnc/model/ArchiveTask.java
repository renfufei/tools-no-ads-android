package com.tools.cnc.model;

import java.io.File;
import java.util.List;

public class ArchiveTask {

    public enum Type {
        COMPRESS, DECOMPRESS
    }

    private final Type type;
    private final List<File> inputFiles;
    private final File outputPath;
    private final String password;
    private final int compressionLevel;

    private ArchiveTask(Builder builder) {
        this.type = builder.type;
        this.inputFiles = builder.inputFiles;
        this.outputPath = builder.outputPath;
        this.password = builder.password;
        this.compressionLevel = builder.compressionLevel;
    }

    public Type getType() { return type; }
    public List<File> getInputFiles() { return inputFiles; }
    public File getOutputPath() { return outputPath; }
    public String getPassword() { return password; }
    public int getCompressionLevel() { return compressionLevel; }

    public static class Builder {
        private Type type;
        private List<File> inputFiles;
        private File outputPath;
        private String password;
        private int compressionLevel = 5;

        public Builder setType(Type type) { this.type = type; return this; }
        public Builder setInputFiles(List<File> files) { this.inputFiles = files; return this; }
        public Builder setOutputPath(File path) { this.outputPath = path; return this; }
        public Builder setPassword(String password) { this.password = password; return this; }
        public Builder setCompressionLevel(int level) { this.compressionLevel = level; return this; }
        public ArchiveTask build() { return new ArchiveTask(this); }
    }
}
