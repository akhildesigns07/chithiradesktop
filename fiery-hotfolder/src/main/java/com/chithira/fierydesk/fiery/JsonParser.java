package com.chithira.fierydesk.fiery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JSON reader (no library needed).
 * Objects -> LinkedHashMap, arrays -> ArrayList, strings -> String,
 * numbers -> String (exactly as written, so ids stay "5598"), true/false -> Boolean, null -> null.
 */
public final class JsonParser {

    private final String s;
    private int i;

    private JsonParser(String s) { this.s = s; }

    public static Object parse(String json) {
        JsonParser p = new JsonParser(json);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.error("Unexpected text after JSON");
        return v;
    }

    /** Value of key as text ("" when missing or null). */
    public static String text(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : v.toString();
    }

    private Object value() {
        ws();
        if (i >= s.length()) throw error("Unexpected end of JSON");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw error("Expected property name");
            String key = string();
            ws();
            expect(':');
            m.put(key, value());
            ws();
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw error("Expected , or }");
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++; // [
        ws();
        if (peek() == ']') { i++; return list; }
        while (true) {
            list.add(value());
            ws();
            char c = next();
            if (c == ']') return list;
            if (c != ',') throw error("Expected , or ]");
        }
    }

    private String string() {
        i++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = next();
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw error("Bad \\u escape");
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> throw error("Bad escape \\" + e);
            }
        }
    }

    private String number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw error("Unexpected character '" + s.charAt(i) + "'");
        return s.substring(start, i);
    }

    private void literal(String word) {
        if (!s.startsWith(word, i)) throw error("Expected " + word);
        i += word.length();
    }

    private void expect(char c) { if (next() != c) throw error("Expected " + c); }
    private char peek() { if (i >= s.length()) throw error("Unexpected end of JSON"); return s.charAt(i); }
    private char next() { char c = peek(); i++; return c; }
    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException(msg + " at position " + i);
    }
}
