package com.chithira.fierydesk.document;

/**
 * Target page for an image. Width/height 0 means "original size": the page is
 * made exactly as big as the image at {@link #ORIGINAL_DPI}.
 */
public record PageSpec(double widthInch, double heightInch, Fit fit, boolean autoRotate) {
    public enum Fit { FIT, FILL }

    public static final double POINTS_PER_INCH = 72.0;
    public static final int ORIGINAL_DPI = 300;

    public boolean isOriginalSize() { return widthInch <= 0 || heightInch <= 0; }

    /** Where and how big the image lands on the page. */
    public record Layout(float pageW, float pageH, float x, float y, float outW, float outH,
                         boolean rotate, int dpi, boolean cropped, boolean margins) {}

    public Layout layout(int imgW, int imgH) {
        if (isOriginalSize()) {
            float w = (float) (imgW * POINTS_PER_INCH / ORIGINAL_DPI);
            float h = (float) (imgH * POINTS_PER_INCH / ORIGINAL_DPI);
            return new Layout(w, h, 0, 0, w, h, false, ORIGINAL_DPI, false, false);
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
