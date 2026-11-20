package com.chithira.fierydesk.core;

import com.chithira.fierydesk.impose.ImposeSettings;

import java.nio.file.Path;

/**
 * A job that is turned into a print-ready PDF sheet before sending:
 * imposed (step and repeat), and/or front + back.
 *
 * @param front     front image (or the only image)
 * @param back      back image, or null for single-sided
 * @param impose    imposition settings, or null to place one image per sheet
 * @param sheetW / sheetH  paper size in points (from the Paper Catalog or the driver)
 * @param paperName shown in logs and on the sheet's info line
 * @param sizing    how a single (not imposed) image is placed on the sheet
 * @param copies    copies when not imposed (imposed jobs use quantity ÷ pieces per sheet)
 */
public record SheetJob(Path front, Path back, ImposeSettings impose, double sheetW, double sheetH,
                       String paperName, Sizing sizing, int copies, boolean allowLowRes) {

    public boolean duplex() { return back != null; }
}
