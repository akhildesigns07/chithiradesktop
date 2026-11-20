package com.chithira.mockfiery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader/writer (no library needed). Numbers are read as text. */
final class Json {
    private Json() {}

    // ------------------------------------------------------------------ write

    static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 0, false);
        return sb.toString();
    }

    static String pretty(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 0, true);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int indent, boolean pretty) {
        if (v == null) sb.append("null");
        else if (v instanceof String s) quote(sb, s);
        else if (v instanceof Number || v instanceof Boolean) sb.append(v);
        else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                nl(sb, indent + 1, pretty);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(pretty ? ": " : ":");
                write(sb, e.getValue(), indent + 1, pretty);
            }
            if (!m.isEmpty()) nl(sb, indent, pretty);
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                nl(sb, indent + 1, pretty);
                write(sb, l.get(i), indent + 1, pretty);
            }
            if (!l.isEmpty()) nl(sb, indent, pretty);
            sb.append(']');
        } else quote(sb, v.toString());
    }

    private static void nl(StringBuilder sb, int indent, boolean pretty) {
        if (!pretty) return;
        sb.append('\n');
        sb.append("  ".repeat(indent));
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------- read

    static Object parse(String s) {
        int[] i = {0};
        ws(s, i);
        Object v = value(s, i);
        ws(s, i);
        if (i[0] != s.length()) throw new IllegalArgumentException("Unexpected text at " + i[0]);
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String s) {
        Object o = parse(s);
        if (!(o instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) o;
    }

    private static Object value(String s, int[] i) {
        ws(s, i);
        if (i[0] >= s.length()) throw new IllegalArgumentException("Unexpected end");
        char c = s.charAt(i[0]);
        if (c == '{') {
            Map<String, Object> m = new LinkedHashMap<>();
            i[0]++; ws(s, i);
            if (s.charAt(i[0]) == '}') { i[0]++; return m; }
            while (true) {
                ws(s, i);
                String k = string(s, i);
                ws(s, i);
                if (s.charAt(i[0]++) != ':') throw new IllegalArgumentException("Expected : at " + i[0]);
                m.put(k, value(s, i));
                ws(s, i);
                char d = s.charAt(i[0]++);
                if (d == '}') return m;
                if (d != ',') throw new IllegalArgumentException("Expected , or } at " + i[0]);
            }
        }
        if (c == '[') {
            List<Object> l = new ArrayList<>();
            i[0]++; ws(s, i);
            if (s.charAt(i[0]) == ']') { i[0]++; return l; }
            while (true) {
                l.add(value(s, i));
                ws(s, i);
                char d = s.charAt(i[0]++);
                if (d == ']') return l;
                if (d != ',') throw new IllegalArgumentException("Expected , or ] at " + i[0]);
            }
        }
        if (c == '"') return string(s, i);
        if (s.startsWith("true", i[0])) { i[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i[0])) { i[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i[0])) { i[0] += 4; return null; }
        int start = i[0];
        while (i[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(i[0])) >= 0) i[0]++;
        if (start == i[0]) throw new IllegalArgumentException("Unexpected '" + c + "' at " + start);
        return s.substring(start, i[0]);
    }

    private static String string(String s, int[] i) {
        if (s.charAt(i[0]) != '"') throw new IllegalArgumentException("Expected string at " + i[0]);
        i[0]++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i[0]++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(i[0]++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i[0], i[0] + 4), 16)); i[0] += 4; }
                default -> sb.append(e);
            }
        }
    }

    private static void ws(String s, int[] i) {
        while (i[0] < s.length() && Character.isWhitespace(s.charAt(i[0]))) i[0]++;
    }
}
