package com.chithira.fierydesk.impose;

/**
 * Imposition choices for one job. All lengths in mm.
 * Crop marks are always printed on the front sheet and never on the back.
 *
 * @param item        finished (trim) size of one piece
 * @param bleedMm     artwork beyond the cut line that the customer's file includes (0 = none)
 * @param gapMm       space between pieces (0 = shared cut lines)
 * @param marginMm    unprintable sheet edge
 * @param allowRotate turn pieces 90° when more fit that way
 * @param forceCols / forceRows   0 = automatic
 * @param fill        true = cover the piece (crop edges if shapes differ), false = fit inside (never cropped)
 * @param quantity    pieces the customer wants (sheets = quantity ÷ pieces per sheet, rounded up)
 * @param backOffsetXmm / backOffsetYmm  nudge the back side to correct press registration
 * @param slug        small info line (file, N-up, side, date) in the sheet margin
 * @param mode        piece size (shrink if needed) or fit the rows × columns to the paper
 */
public record ImposeSettings(Preset item, double bleedMm, double gapMm, double marginMm, boolean allowRotate,
                             int forceCols, int forceRows, boolean fill, int quantity,
                             double backOffsetXmm, double backOffsetYmm, boolean slug, Mode mode) {

    /** How the grid is sized. Pieces always keep their proportions and are centred on the sheet. */
    public enum Mode {
        /** Pieces at their real size; shrunk only when they (or the rows × columns asked for) don't fit. */
        PIECE_SIZE("Piece size – shrink only if it doesn't fit"),
        /** The rows × columns asked for are resized (up or down) to fill the paper. */
        FIT_GRID("Fit rows × columns to the paper");

        private final String label;
        Mode(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    /** Older callers: piece-size mode. */
    public ImposeSettings(Preset item, double bleedMm, double gapMm, double marginMm, boolean allowRotate,
                          int forceCols, int forceRows, boolean fill, int quantity,
                          double backOffsetXmm, double backOffsetYmm, boolean slug) {
        this(item, bleedMm, gapMm, marginMm, allowRotate, forceCols, forceRows, fill, quantity,
                backOffsetXmm, backOffsetYmm, slug, Mode.PIECE_SIZE);
    }

    public static final double PT_PER_MM = 72 / 25.4;

    public ImposeSettings withItem(Preset p) {
        return new ImposeSettings(p, bleedMm, gapMm, marginMm, allowRotate,
                forceCols, forceRows, fill, quantity, backOffsetXmm, backOffsetYmm, slug, mode);
    }
}
