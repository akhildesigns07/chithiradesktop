package com.chithira.fierydesk.hub;

import com.chithira.fierydesk.fiery.Json;

import java.util.Collection;
import java.util.Map;

/**
 * Writes JSON from plain Java values (no library needed): Map → object, Collection → array,
 * String, Number, Boolean and null. Map entries with a null value are written as {@code null}.
 */
public final class JsonWriter {

    private JsonWriter() {}

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            out.append(Json.quote(text));
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(Json.quote(String.valueOf(entry.getKey()))).append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Collection<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) out.append(',');
                first = false;
                append(out, item);
            }
            out.append(']');
        } else {
            out.append(Json.quote(value.toString()));
        }
    }
}
