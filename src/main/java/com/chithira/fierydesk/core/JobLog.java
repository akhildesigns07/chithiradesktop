package com.chithira.fierydesk.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Permanent record of every job, one file per month:
 *   <user home>/.chithira-fiery/logs/fiery-jobs-2026-11.log
 * Each entry has the job details and the full Fiery response.
 */
public final class JobLog {

    public static final Path DIR = AppConfig.DIR.resolve("logs");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private JobLog() {}

    public static Path currentFile() {
        return DIR.resolve("fiery-jobs-" + LocalDateTime.now().format(MONTH) + ".log");
    }

    public static synchronized void sent(JobRequest r, long fileSize, String submittedAs, Integer dpi,
                                         String jobId, String fileAction, String response) {
        write("SENT", r, fileSize,
                "submitted as: " + submittedAs + (dpi != null ? " (" + dpi + " DPI)" : "")
                        + "\n  fiery job id: " + (jobId == null ? "?" : jobId)
                        + "\n  file: " + fileAction
                        + "\n  response: " + oneLine(response));
    }

    public static synchronized void failed(JobRequest r, long fileSize, String error, String response, String fileAction) {
        write("FAILED", r, fileSize,
                "error: " + oneLine(error)
                        + (response == null || response.isBlank() ? "" : "\n  response: " + oneLine(response))
                        + "\n  file: " + fileAction);
    }

    /** A file printed through the printer driver (Ctrl+P method). */
    public static synchronized void driverResult(boolean ok, Path file, long size, String printer, String paper,
                                                 String sizing, int copies, boolean combined, String message,
                                                 String fileAction) {
        append(LocalDateTime.now().format(TIME) + " | " + (ok ? "PRINTED" : "FAILED") + " (driver) | "
                + file.getFileName() + " (" + humanSize(size) + ")"
                + "\n  printer: " + printer + " | paper: " + paper + " | sizing: " + sizing
                + " | copies: " + copies + " | " + (combined ? "combined batch job" : "own job")
                + "\n  result: " + oneLine(message)
                + "\n  file: " + fileAction);
    }

    public static synchronized void skipped(Path file, String fileAction) {
        append(LocalDateTime.now().format(TIME) + " | SKIPPED | " + file.getFileName() + "\n  file: " + fileAction);
    }

    private static void write(String status, JobRequest r, long size, String details) {
        String paper = r.paper() == null ? "-" : r.paper().name() + " [" + r.paper().id() + "]";
        append(LocalDateTime.now().format(TIME) + " | " + status + " | " + r.file().getFileName()
                + " (" + humanSize(size) + ")"
                + "\n  job name: " + r.jobName()
                + " | paper: " + paper
                + " | sizing: " + r.sizing().name()
                + " | copies: " + r.copies()
                + " | queue: " + AppConfig.QUEUE
                + (r.instructions() == null || r.instructions().isBlank() ? "" : " | instructions: " + oneLine(r.instructions()))
                + "\n  " + details);
    }

    private static void append(String entry) {
        try {
            Files.createDirectories(DIR);
            Files.writeString(currentFile(), entry + System.lineSeparator() + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logging must never stop a print job
        }
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace("\r", " ").replace("\n", " ").trim();
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
