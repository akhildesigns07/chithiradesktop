package com.chithira.fierydesk.hub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Job reports waiting to reach the hub, one JSON file each in {@code <user home>/.chithira-fiery/outbox}.
 * A report is written here first and deleted only after the hub has saved it, so nothing is lost
 * when the hub or the network is down; printing never waits for the hub.
 * Reports the hub refuses (invalid data) move to {@code outbox/rejected} with an {@code .error.txt}.
 */
public class Outbox {

    /** What one delivery attempt did. */
    public record FlushResult(int delivered, int rejected, int waiting, HubException stoppedBy) {}

    private final Path directory;
    private final Path rejectedDirectory;

    public Outbox(Path directory) {
        this.directory = directory;
        this.rejectedDirectory = directory.resolve("rejected");
    }

    /** Saves the report to disk (safe against a crash half-way through writing). */
    public synchronized void add(JobReport report) throws IOException {
        Files.createDirectories(directory);
        String fileName = report.sentAt().toEpochMilli() + "-" + report.clientJobUuid() + ".json";
        Path temporary = directory.resolve(fileName + ".tmp");
        Files.writeString(temporary, report.toJson(), StandardCharsets.UTF_8);
        Files.move(temporary, directory.resolve(fileName), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Reports not yet delivered. */
    public synchronized int waiting() {
        return pendingFiles().size();
    }

    /**
     * Sends waiting reports, oldest first. Stops at the first temporary problem (hub down, wrong key)
     * so the order is kept; the next attempt continues from there.
     */
    public synchronized FlushResult deliver(HubClient client) throws InterruptedException {
        int delivered = 0, rejected = 0;
        HubException stoppedBy = null;
        for (Path file : pendingFiles()) {
            try {
                client.reportJob(Files.readString(file, StandardCharsets.UTF_8));
                Files.deleteIfExists(file);
                delivered++;
            } catch (HubException e) {
                if (e.isTemporary() || e.isKeyProblem()) {
                    stoppedBy = e;
                    break;
                }
                moveToRejected(file, e);
                rejected++;
            } catch (IOException e) {
                stoppedBy = new HubException(0, "Cannot read " + file.getFileName() + ": " + e.getMessage(), null);
                break;
            }
        }
        return new FlushResult(delivered, rejected, pendingFiles().size(), stoppedBy);
    }

    private void moveToRejected(Path file, HubException reason) {
        try {
            Files.createDirectories(rejectedDirectory);
            Files.move(file, rejectedDirectory.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(rejectedDirectory.resolve(file.getFileName() + ".error.txt"),
                    reason.getMessage() + System.lineSeparator() + (reason.responseBody() == null ? "" : reason.responseBody()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            // leave it in the outbox; it will be tried again
        }
    }

    private List<Path> pendingFiles() {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(directory)) return files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
            stream.forEach(files::add);
        } catch (IOException e) {
            return files;
        }
        files.sort(null);          // names start with the send time, so this is oldest first
        return files;
    }
}
