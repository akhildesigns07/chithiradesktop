package com.chithira.mockfiery;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Everything the mock's parts share: settings, jobs, sessions, test switches and the activity log. */
final class MockState {

    final MockConfig cfg;
    final JobStore jobs;
    final Previews previews;
    final Path paperCatalogFile;

    // test switches set from the web page
    volatile boolean offline;
    volatile int slowSeconds;
    volatile boolean rejectNext;
    volatile boolean error500Next;

    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Deque<Map<String, Object>> activity = new ArrayDeque<>();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    MockState(MockConfig cfg) throws IOException {
        this.cfg = cfg;
        Files.createDirectories(cfg.dataDir);
        this.jobs = new JobStore(cfg.dataDir);
        this.previews = new Previews(cfg.dataDir);
        this.paperCatalogFile = cfg.dataDir.resolve("papercatalog.json");
        if (!Files.exists(paperCatalogFile)) {
            try (InputStream in = MockState.class.getResourceAsStream("/mockfiery/default-papercatalog.json")) {
                Files.write(paperCatalogFile, in.readAllBytes());
            }
        }
    }

    // ---------------------------------------------------------------- sessions

    String newSession() {
        String id = UUID.randomUUID().toString().replace("-", "");
        sessions.put(id, System.currentTimeMillis() + cfg.sessionMinutes * 60_000L);
        return id;
    }

    boolean validSession(String id) {
        if (id == null) return false;
        Long until = sessions.get(id);
        if (until == null) return false;
        if (until < System.currentTimeMillis()) { sessions.remove(id); return false; }
        return true;
    }

    void endSession(String id) { if (id != null) sessions.remove(id); }

    void expireAllSessions() { sessions.clear(); }

    int sessionCount() { return sessions.size(); }

    // ---------------------------------------------------------------- paper catalog

    /** Re-read on every call, so edits to papercatalog.json apply immediately. */
    String paperCatalogJson() throws IOException {
        return Files.readString(paperCatalogFile, StandardCharsets.UTF_8);
    }

    /** id -> name of every paper in the catalog. */
    Map<String, String> papers() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            collect(Json.parse(paperCatalogJson()), out);
        } catch (Exception ignored) {
            // broken file: no papers
        }
        return out;
    }

    private static void collect(Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> m) {
            if (m.containsKey("name") && (m.containsKey("id") || m.containsKey("MID"))) {
                Object id = m.containsKey("id") ? m.get("id") : m.get("MID");
                out.put(String.valueOf(id), String.valueOf(m.get("name")));
                return;
            }
            m.values().forEach(v -> collect(v, out));
        } else if (node instanceof List<?> l) {
            l.forEach(v -> collect(v, out));
        }
    }

    // ---------------------------------------------------------------- activity log

    synchronized void log(String method, String path, int status, String note) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("time", LocalTime.now().format(TIME));
        e.put("request", method + " " + path);
        e.put("status", status);
        e.put("note", note == null ? "" : note);
        activity.addFirst(e);
        while (activity.size() > 60) activity.removeLast();
        System.out.println(e.get("time") + "  " + status + "  " + method + " " + path + (note == null || note.isEmpty() ? "" : "  - " + note));
    }

    synchronized List<Map<String, Object>> activity() { return new ArrayList<>(activity); }
}
