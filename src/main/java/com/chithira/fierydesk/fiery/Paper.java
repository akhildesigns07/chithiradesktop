package com.chithira.fierydesk.fiery;

import java.util.Map;

/**
 * One Paper Catalog entry from GET /live/api/v5/papercatalog.
 * Dimension is in points ("936.0 1368.0" = 13 x 19 in).
 */
public record Paper(String id, String name, double widthPt, double heightPt,
                    String weight, String mediaType, String coating, String color, String tray) {

    public static Paper fromMap(Map<?, ?> m) {
        String id = JsonParser.text(m, "id");
        if (id.isBlank()) id = JsonParser.text(m, "MID");
        double w = 0, h = 0;
        String[] dim = JsonParser.text(m, "Dimension").trim().split("\\s+");
        if (dim.length == 2) {
            try {
                w = Double.parseDouble(dim[0]);
                h = Double.parseDouble(dim[1]);
            } catch (NumberFormatException ignored) {
                // leave 0 = size unknown
            }
        }
        String mediaType = JsonParser.text(m, "MediaTypeDetails");
        if (mediaType.isBlank()) mediaType = JsonParser.text(m, "MediaType");
        return new Paper(id, JsonParser.text(m, "name"), w, h,
                JsonParser.text(m, "Weight"), mediaType,
                JsonParser.text(m, "FrontCoatings"), JsonParser.text(m, "MediaColorName"),
                JsonParser.text(m, "TrayNo"));
    }

    public boolean hasSize() { return widthPt > 0 && heightPt > 0; }
    public double widthInch() { return widthPt / 72.0; }
    public double heightInch() { return heightPt / 72.0; }

    public String sizeLabel() {
        if (!hasSize()) return "size unknown";
        return fmt(widthInch()) + " × " + fmt(heightInch()) + " in ("
                + Math.round(widthPt / 72.0 * 25.4) + " × " + Math.round(heightPt / 72.0 * 25.4) + " mm)";
    }

    public String details() {
        StringBuilder sb = new StringBuilder(sizeLabel());
        if (!weight.isBlank()) sb.append(", ").append(weight).append(" gsm");
        if (!mediaType.isBlank()) sb.append(", ").append(mediaType);
        if (!coating.isBlank() && !coating.equalsIgnoreCase("none")) sb.append(", ").append(coating);
        if (!tray.isBlank()) sb.append(", tray ").append(tray);
        return sb.toString();
    }

    @Override
    public String toString() { return name + "   [" + id + "]"; }

    private static String fmt(double d) {
        double r = Math.round(d * 100) / 100.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}
