package com.tools.cnc.model;

public class TaskResult {

    private final boolean success;
    private final String outputPath;
    private final String errorMessage;
    private final long duration;

    private TaskResult(boolean success, String outputPath, String errorMessage, long duration) {
        this.success = success;
        this.outputPath = outputPath;
        this.errorMessage = errorMessage;
        this.duration = duration;
    }

    public static TaskResult success(String outputPath, long duration) {
        return new TaskResult(true, outputPath, null, duration);
    }

    public static TaskResult error(String message) {
        return new TaskResult(false, null, message, 0);
    }

    public boolean isSuccess() { return success; }
    public String getOutputPath() { return outputPath; }
    public String getErrorMessage() { return errorMessage; }
    public long getDuration() { return duration; }
}
