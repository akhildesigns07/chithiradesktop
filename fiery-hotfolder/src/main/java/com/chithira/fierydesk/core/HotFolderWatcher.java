package com.chithira.fierydesk.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Watches one folder. When a new file has finished copying (size stable and the
 * file no longer locked by the copy), it is handed to the listener exactly once.
 * Sub-folders (Sent, Failed, Skipped) are ignored.
 */
public final class HotFolderWatcher implements AutoCloseable {

    public static final String SENT = "Sent", FAILED = "Failed", SKIPPED = "Skipped";

    private final Path folder;
    private final Consumer<Path> onReady;
    private final Consumer<String> onError;

    private final Set<Path> known = ConcurrentHashMap.newKeySet();
    private final Map<Path, long[]> lastSeen = new ConcurrentHashMap<>(); // size, mtime, stableCount
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hotfolder-stability");
        t.setDaemon(true);
        return t;
    });

    private WatchService watchService;
    private Thread watchThread;
    private volatile boolean running;

    public HotFolderWatcher(Path folder, Consumer<Path> onReady, Consumer<String> onError) {
        this.folder = folder.toAbsolutePath().normalize();
        this.onReady = onReady;
        this.onError = onError;
    }

    public void start(boolean includeExisting) throws IOException {
        Files.createDirectories(folder);
        for (String sub : new String[]{SENT, FAILED, SKIPPED}) Files.createDirectories(folder.resolve(sub));

        watchService = folder.getFileSystem().newWatchService();
        folder.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
        running = true;

        if (includeExisting) {
            try (Stream<Path> files = Files.list(folder)) {
                files.forEach(this::consider);
            }
        }

        // Safety net: some network drives miss watch events; rescan every 10 s.
        scheduler.scheduleWithFixedDelay(this::rescan, 10, 10, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::checkStability, 1, 1, TimeUnit.SECONDS);

        watchThread = new Thread(this::loop, "hotfolder-watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    /** Call after the file was moved away, so a new file with the same name is picked up again. */
    public void forget(Path file) {
        Path p = file.toAbsolutePath().normalize();
        known.remove(p);
        lastSeen.remove(p);
    }

    public Path folder() { return folder; }

    @Override
    public void close() {
        running = false;
        scheduler.shutdownNow();
        try { if (watchService != null) watchService.close(); } catch (IOException ignored) {}
        if (watchThread != null) watchThread.interrupt();
    }

    // ----------------------------------------------------------------------

    private void loop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
            for (WatchEvent<?> ev : key.pollEvents()) {
                if (ev.kind() == StandardWatchEventKinds.OVERFLOW) { rescan(); continue; }
                consider(folder.resolve((Path) ev.context()));
            }
            if (!key.reset()) {
                onError.accept("Hot folder is no longer accessible: " + folder);
                return;
            }
        }
    }

    private void rescan() {
        if (!running) return;
        try (Stream<Path> files = Files.list(folder)) {
            files.forEach(this::consider);
        } catch (IOException e) {
            onError.accept("Cannot read hot folder: " + e.getMessage());
        }
    }

    private void consider(Path file) {
        Path p = file.toAbsolutePath().normalize();
        if (!isCandidate(p) || known.contains(p)) return;
        lastSeen.putIfAbsent(p, new long[]{-1, -1, 0});
    }

    private void checkStability() {
        for (Map.Entry<Path, long[]> e : lastSeen.entrySet()) {
            Path p = e.getKey();
            long[] s = e.getValue();
            try {
                if (!Files.exists(p)) { lastSeen.remove(p); continue; }
                long size = Files.size(p);
                FileTime mt = Files.getLastModifiedTime(p);
                if (size > 0 && size == s[0] && mt.toMillis() == s[1]) {
                    s[2]++;
                } else {
                    s[0] = size; s[1] = mt.toMillis(); s[2] = 0;
                }
                // Unchanged for 2 checks (~2 s) and not locked by the copying program
                if (s[2] >= 2 && notLocked(p) && known.add(p)) {
                    lastSeen.remove(p);
                    onReady.accept(p);
                }
            } catch (IOException ex) {
                // file vanished or is still being written; try again next tick
            } catch (RuntimeException ex) {
                onError.accept("Problem with " + p.getFileName() + ": " + ex.getMessage());
            }
        }
    }

    private boolean isCandidate(Path p) {
        if (!folder.equals(p.getParent()) || !Files.isRegularFile(p)) return false;
        String n = p.getFileName().toString();
        String lower = n.toLowerCase(Locale.ROOT);
        if (n.startsWith(".") || n.startsWith("~$")) return false;
        if (lower.equals("thumbs.db") || lower.equals("desktop.ini")) return false;
        if (lower.endsWith(".tmp") || lower.endsWith(".part") || lower.endsWith(".crdownload")
                || lower.endsWith(".partial") || lower.endsWith(".download")) return false;
        try { if (Files.isHidden(p)) return false; } catch (IOException ignored) {}
        return true;
    }

    /** On Windows a file still being copied can't be opened for writing. Nothing is written. */
    private static boolean notLocked(Path p) {
        try (FileChannel ignored = FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
