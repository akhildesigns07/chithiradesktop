package com.chithira.fierydesk.fiery;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tiny JSON helper so the app needs no JSON library. */
public final class Json {
    private Json() {}

    /** {"key":"value",...} with correct escaping. */
    public static String object(Map<String, String> values) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        return sb.append('}').toString();
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(?:\"([^\"]*)\"|(-?\\d+))");

    /**
     * First "id" value in a JSON response. Fiery response layouts vary by version;
     * check one real upload response and tighten this if it picks the wrong id.
     */
    public static String firstId(String json) {
        if (json == null) return null;
        Matcher m = ID.matcher(json);
        if (!m.find()) return null;
        return m.group(1) != null ? m.group(1) : m.group(2);
    }
}
