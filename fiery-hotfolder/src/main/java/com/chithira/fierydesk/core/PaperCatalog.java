package com.chithira.fierydesk.core;

import com.chithira.fierydesk.fiery.FieryClient;
import com.chithira.fierydesk.fiery.JsonParser;
import com.chithira.fierydesk.fiery.Paper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Paper Catalog loaded from the Fiery once at startup (and on "Refresh papers").
 * The last good response is saved to disk, so the app still works if the Fiery
 * is unreachable when the app starts.
 */
public final class PaperCatalog {

    public static final Path CACHE = AppConfig.DIR.resolve("papercatalog.json");

    private volatile List<Paper> papers = List.of();
    private volatile Instant updated;
    private volatile boolean fromCache;

    /** Loads the copy saved on disk. Returns false if there is none. */
    public boolean loadCache() {
        if (!Files.exists(CACHE)) return false;
        try {
            List<Paper> list = parse(Files.readString(CACHE, StandardCharsets.UTF_8));
            if (list.isEmpty()) return false;
            papers = list;
            updated = Files.getLastModifiedTime(CACHE).toInstant();
            fromCache = true;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Fetches GET /papercatalog from the Fiery and refreshes the disk cache. */
    public void refresh(FieryClient fiery) throws IOException, InterruptedException {
        String json = fiery.getPaperCatalogJson();
        List<Paper> list = parse(json);
        if (list.isEmpty()) throw new IOException("The Fiery returned an empty paper catalog");
        Files.createDirectories(CACHE.getParent());
        Files.writeString(CACHE, json, StandardCharsets.UTF_8);
        papers = list;
        updated = Instant.now();
        fromCache = false;
    }

    public List<Paper> papers() { return papers; }
    public Instant updated() { return updated; }
    public boolean isFromCache() { return fromCache; }

    public Optional<Paper> byId(String id) {
        if (id == null) return Optional.empty();
        return papers.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** Finds the list of paper entries wherever it sits in the response (e.g. data.items). */
    public static List<Paper> parse(String json) {
        Object root = JsonParser.parse(json);
        List<?> items = findPaperArray(root);
        List<Paper> out = new ArrayList<>();
        if (items == null) return out;
        for (Object o : items) {
            if (o instanceof Map<?, ?> m) {
                Paper p = Paper.fromMap(m);
                if (!p.id().isBlank() && !p.name().isBlank()) out.add(p);
            }
        }
        out.sort(Comparator.comparing(Paper::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static List<?> findPaperArray(Object node) {
        if (node instanceof List<?> list) {
            if (!list.isEmpty() && list.get(0) instanceof Map<?, ?> m
                    && (m.containsKey("MID") || (m.containsKey("id") && m.containsKey("name")))) {
                return list;
            }
            for (Object o : list) {
                List<?> found = findPaperArray(o);
                if (found != null) return found;
            }
        } else if (node instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                List<?> found = findPaperArray(v);
                if (found != null) return found;
            }
        }
        return null;
    }
}
