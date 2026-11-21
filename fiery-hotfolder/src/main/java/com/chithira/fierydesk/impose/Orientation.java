package com.chithira.fierydesk.impose;

/** Which way the paper sheet is turned when imposing, whatever the shape of the file. */
public enum Orientation {
    PORTRAIT("Portrait (tall)"),
    LANDSCAPE("Landscape (wide)");

    private final String label;

    Orientation(String label) { this.label = label; }

    /** Sheet width and height (points) turned to this orientation. */
    public double[] apply(double w, double h) {
        double shortSide = Math.min(w, h), longSide = Math.max(w, h);
        return this == PORTRAIT ? new double[]{shortSide, longSide} : new double[]{longSide, shortSide};
    }

    public static Orientation parse(String s, Orientation fallback) {
        try {
            return s == null || s.isBlank() ? fallback : valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @Override public String toString() { return label; }
}
