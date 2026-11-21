package com.chithira.fierydesk.document;

/**
 * Target page for an image. Width/height 0 means "actual size": the page is made exactly as big as
 * the image at {@code originalDpi} (the DPI stored in the file, or {@link #ORIGINAL_DPI}).
 */
public record PageSpec(double widthInch, double heightInch, Fit fit, boolean autoRotate, double originalDpi) {
    public enum Fit { FIT, FILL }

    public static final double POINTS_PER_INCH = 72.0;
    /** DPI used for an image's actual size when the file stores none. */
    public static final int ORIGINAL_DPI = 300;

    public PageSpec(double widthInch, double heightInch, Fit fit, boolean autoRotate) {
        this(widthInch, heightInch, fit, autoRotate, ORIGINAL_DPI);
    }

    /** Page = the image's own size at this DPI. */
    public static PageSpec actualSize(double dpi) {
        return new PageSpec(0, 0, Fit.FIT, false, dpi > 0 ? dpi : ORIGINAL_DPI);
    }

    public boolean isOriginalSize() { return widthInch <= 0 || heightInch <= 0; }

    /** Where and how big the image lands on the page. */
    public record Layout(float pageW, float pageH, float x, float y, float outW, float outH,
                         boolean rotate, int dpi, boolean cropped, boolean margins) {}

    public Layout layout(int imgW, int imgH) {
        if (isOriginalSize()) {
            float w = (float) (imgW * POINTS_PER_INCH / originalDpi);
            float h = (float) (imgH * POINTS_PER_INCH / originalDpi);
            return new Layout(w, h, 0, 0, w, h, false, (int) Math.round(originalDpi), false, false);
        }
        float pageW = (float) (widthInch * POINTS_PER_INCH);
        float pageH = (float) (heightInch * POINTS_PER_INCH);

        boolean rotate = autoRotate && imgW != imgH && ((imgW > imgH) != (pageW > pageH));
        float drawnW = rotate ? imgH : imgW;
        float drawnH = rotate ? imgW : imgH;

        float scale = fit == Fit.FILL
                ? Math.max(pageW / drawnW, pageH / drawnH)
                : Math.min(pageW / drawnW, pageH / drawnH);
        float outW = drawnW * scale;
        float outH = drawnH * scale;
        int dpi = Math.round((float) (drawnW / (outW / POINTS_PER_INCH)));

        boolean cropped = outW > pageW + 0.5f || outH > pageH + 0.5f;
        boolean margins = outW < pageW - 0.5f || outH < pageH - 0.5f;
        return new Layout(pageW, pageH, (pageW - outW) / 2f, (pageH - outH) / 2f, outW, outH,
                rotate, dpi, cropped, margins);
    }
}
