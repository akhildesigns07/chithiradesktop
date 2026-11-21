package com.chithira.printhub.fiery;

import com.chithira.printhub.jobs.PrintJobStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the job state from a Fiery answer ({@code printhub.fiery.status-path}) and maps it to a hub status
 * ({@code printhub.fiery.status-map}). Unknown states are kept as raw text and do not change the status.
 */
@Component
public class FieryStatusMapper {

    private final FieryProperties properties;

    public FieryStatusMapper(FieryProperties properties) { this.properties = properties; }

    /** The raw state text, e.g. "held", or empty when the path is not in the answer. */
    public Optional<String> rawState(JsonNode body) {
        JsonNode node = body;
        for (String part : properties.getStatusPath().split("\\.")) {
            if (node == null) return Optional.empty();
            node = node.get(part);
        }
        return node == null || node.isNull() ? Optional.empty() : Optional.of(node.asText().trim());
    }

    /** Hub status for a raw Fiery state, if the state is in the map. */
    public Optional<PrintJobStatus> map(String rawState) {
        String key = rawState.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : properties.getStatusMap().entrySet()) {
            if (entry.getKey().trim().toLowerCase(Locale.ROOT).equals(key)) {
                return Optional.of(PrintJobStatus.valueOf(entry.getValue().trim().toUpperCase(Locale.ROOT)));
            }
        }
        return Optional.empty();
    }
}
