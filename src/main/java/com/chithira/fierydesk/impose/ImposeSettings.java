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
 * @param fill        true = cover the piece (crop edges if shapes differ), false = fit inside
 * @param quantity    pieces the customer wants (sheets = quantity ÷ pieces per sheet, rounded up)
 * @param backOffsetXmm / backOffsetYmm  nudge the back side to correct press registration
 * @param slug        small info line (file, N-up, side, date) in the sheet margin
 */
public record ImposeSettings(Preset item, double bleedMm, double gapMm, double marginMm, boolean allowRotate,
                             int forceCols, int forceRows, boolean fill, int quantity,
                             double backOffsetXmm, double backOffsetYmm, boolean slug) {

    public static final double PT_PER_MM = 72 / 25.4;

    public ImposeSettings withItem(Preset p) {
        return new ImposeSettings(p, bleedMm, gapMm, marginMm, allowRotate,
                forceCols, forceRows, fill, quantity, backOffsetXmm, backOffsetYmm, slug);
    }
}
