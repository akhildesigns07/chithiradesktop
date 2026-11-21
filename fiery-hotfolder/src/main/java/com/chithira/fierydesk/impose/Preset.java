package com.chithira.fierydesk.impose;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A finished item size, e.g. "Visiting card 89×55 mm". Sizes are stored in mm,
 * landscape or portrait as written.
 */
public record Preset(String name, double widthMm, double heightMm) {

    /** Built-in list; can be replaced with impose.presets in the config file. */
    public static final String DEFAULTS =
            "Visiting card:89x55mm;Visiting card (US):3.5x2in;ID card (CR80):86x54mm;"
            + "Postcard 4x6:6x4in;Photo 5x7:7x5in;Square 4x4:4x4in;Sticker 2x2:2x2in;"
            + "A6:148x105mm;A5:210x148mm;A4:297x210mm;DL flyer:210x99mm;Bookmark:6x2in";

    /** Parses "Name:WxHmm;Name:WxHin;…" (units mm, cm or in). Bad entries are skipped. */
    public static List<Preset> parse(String text) {
        List<Preset> out = new ArrayList<>();
        if (text == null) return out;
        for (String entry : text.split(";")) {
            int colon = entry.lastIndexOf(':');
            if (colon <= 0) continue;
            String name = entry.substring(0, colon).trim();
            String size = entry.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            double factor = 1;
            if (size.endsWith("mm")) size = size.substring(0, size.length() - 2);
            else if (size.endsWith("cm")) { size = size.substring(0, size.length() - 2); factor = 10; }
            else if (size.endsWith("in")) { size = size.substring(0, size.length() - 2); factor = 25.4; }
            String[] wh = size.split("[x×*]");
            if (wh.length != 2) continue;
            try {
                double w = Double.parseDouble(wh[0].trim()) * factor;
                double h = Double.parseDouble(wh[1].trim()) * factor;
                if (w > 0 && h > 0 && !name.isEmpty()) out.add(new Preset(name, w, h));
            } catch (NumberFormatException ignored) {
                // skip bad entry
            }
        }
        return out;
    }

    /** Same size turned to match the image's orientation. */
    public Preset oriented(boolean landscape) {
        boolean isLandscape = widthMm >= heightMm;
        return isLandscape == landscape ? this : new Preset(name, heightMm, widthMm);
    }

    public String sizeLabel() {
        return fmt(widthMm) + " × " + fmt(heightMm) + " mm";
    }

    @Override
    public String toString() { return name + "  (" + sizeLabel() + ")"; }

    static String fmt(double mm) {
        double r = Math.round(mm * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}
