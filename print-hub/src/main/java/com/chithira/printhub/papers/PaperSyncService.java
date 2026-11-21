package com.chithira.printhub.papers;

import com.chithira.printhub.audit.AuditService;
import com.chithira.printhub.common.BusinessException;
import com.chithira.printhub.fiery.FieryApiClient;
import com.chithira.printhub.fiery.FieryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the Fiery's Paper Catalog into the {@code paper} table: new entries are added, changed ones updated,
 * missing ones marked inactive. Runs every {@code printhub.fiery.paper-sync-hours} and from the Paper page.
 */
@Service
public class PaperSyncService {

    /** What a sync did. */
    public record SyncResult(int added, int updated, int deactivated, int total) {
        public String summary() {
            return total + " papers on the Fiery: " + added + " new, " + updated + " changed, " + deactivated + " no longer there";
        }
    }

    private static final Logger log = LoggerFactory.getLogger(PaperSyncService.class);

    private final FieryProperties properties;
    private final FieryApiClient fiery;
    private final PaperRepository papers;
    private final AuditService audit;
    private volatile Instant lastSyncAt;
    private volatile String lastError;

    public PaperSyncService(FieryProperties properties, FieryApiClient fiery, PaperRepository papers, AuditService audit) {
        this.properties = properties;
        this.fiery = fiery;
        this.papers = papers;
        this.audit = audit;
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "#{${printhub.fiery.paper-sync-hours:6} * 3600000}")
    public void scheduledSync() {
        if (!properties.isConfigured()) return;
        try {
            SyncResult result = sync();
            log.info("Paper catalog synced: {}", result.summary());
        } catch (Exception e) {
            log.warn("Paper catalog sync failed: {}", e.getMessage());
        }
    }

    /** Reads the catalog now. */
    @Transactional
    public SyncResult sync() throws IOException, InterruptedException {
        if (!properties.isConfigured()) {
            throw new BusinessException("The Fiery connection is not set up (printhub.fiery in application.yml)");
        }
        try {
            JsonNode items = findPaperArray(fiery.getPaperCatalog());
            if (items == null) throw new IOException("No paper list in the Fiery's answer");
            int added = 0, updated = 0, deactivated = 0;
            Set<String> seen = new HashSet<>();
            for (JsonNode item : items) {
                String pcmid = text(item, "id");
                if (pcmid == null) pcmid = text(item, "MID");
                String name = text(item, "name");
                if (pcmid == null || name == null) continue;
                seen.add(pcmid);
                double[] size = dimension(text(item, "Dimension"));
                String mediaType = text(item, "MediaTypeDetails") != null ? text(item, "MediaTypeDetails") : text(item, "MediaType");
                Paper paper = papers.findByFieryPcmid(pcmid).orElse(null);
                boolean isNew = paper == null;
                if (isNew) paper = new Paper(pcmid);
                var before = isNew ? null : paper.toAudit();
                boolean changed = paper.update(name, size == null ? null : size[0], size == null ? null : size[1],
                        text(item, "Weight"), mediaType, text(item, "FrontCoatings"), text(item, "MediaColorName"), text(item, "TrayNo"));
                papers.save(paper);
                if (isNew) {
                    added++;
                    audit.record("PAPER_ADDED", "PAPER", paper.getId(), "Paper " + name + " [" + pcmid + "] from the Fiery", null, paper.toAudit());
                } else if (changed) {
                    updated++;
                    audit.record("PAPER_UPDATED", "PAPER", paper.getId(), "Paper " + name + " [" + pcmid + "] changed on the Fiery", before, paper.toAudit());
                }
            }
            for (Paper paper : papers.findAll()) {
                if (paper.isActive() && !seen.contains(paper.getFieryPcmid())) {
                    var before = paper.toAudit();
                    paper.deactivate();
                    deactivated++;
                    audit.record("PAPER_DEACTIVATED", "PAPER", paper.getId(), "Paper " + paper.getName() + " no longer on the Fiery", before, paper.toAudit());
                }
            }
            lastSyncAt = Instant.now();
            lastError = null;
            return new SyncResult(added, updated, deactivated, seen.size());
        } catch (IOException e) {
            lastError = e.getMessage();
            throw e;
        }
    }

    public List<Paper> all() { return papers.findAllByOrderByActiveDescNameAsc(); }

    public Instant getLastSyncAt() { return lastSyncAt; }

    public String getLastError() { return lastError; }

    /** The list of paper entries wherever it sits in the answer (e.g. data.items). */
    static JsonNode findPaperArray(JsonNode node) {
        if (node == null) return null;
        if (node.isArray()) {
            if (!node.isEmpty() && node.get(0).isObject() && (node.get(0).has("MID") || (node.get(0).has("id") && node.get(0).has("name")))) {
                return node;
            }
            for (JsonNode child : node) {
                JsonNode found = findPaperArray(child);
                if (found != null) return found;
            }
        } else if (node.isObject()) {
            for (JsonNode child : node) {
                JsonNode found = findPaperArray(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** "936.0 1368.0" → [936, 1368], or null. */
    private static double[] dimension(String text) {
        if (text == null) return null;
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 2) return null;
        try {
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode item, String field) {
        JsonNode value = item.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
