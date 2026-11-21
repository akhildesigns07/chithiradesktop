package com.chithira.printhub.common;

/**
 * Small text helpers shared by all modules.
 */
public final class TextUtils {
    private TextUtils() {}

    /** Trimmed text, or null when blank. */
    public static String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
