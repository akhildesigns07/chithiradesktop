package com.chithira.fierydesk.fiery;

import java.util.LinkedHashMap;
import java.util.Map;

/** Form fields for POST /live/api/v5/jobs. */
public final class JobOptions {
    private String queue = "hold";
    private String preset;
    private String pcmid;
    private final Map<String, String> attributes = new LinkedHashMap<>();

    public JobOptions queue(String v) { queue = blankToNull(v); return this; }
    public JobOptions preset(String v) { preset = blankToNull(v); return this; }
    public JobOptions pcmid(String v) { pcmid = blankToNull(v); return this; }
    public JobOptions copies(int n) { return attribute("num copies", String.valueOf(n)); }
    public JobOptions instructions(String v) { return attribute("instructions", v); }

    public JobOptions attribute(String key, String value) {
        if (value != null && !value.isBlank()) attributes.put(key, value.trim());
        return this;
    }

    public String queue() { return queue; }
    public String preset() { return preset; }
    public String pcmid() { return pcmid; }
    public Map<String, String> attributes() { return attributes; }

    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }
}
