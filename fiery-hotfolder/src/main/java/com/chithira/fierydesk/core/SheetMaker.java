package com.chithira.fierydesk.core;

import com.chithira.fierydesk.document.DocumentException;
import com.chithira.fierydesk.document.DocumentNormalizer;
import com.chithira.fierydesk.document.PageSpec;
import com.chithira.fierydesk.impose.ImposeSettings;
import com.chithira.fierydesk.impose.SheetBuilder;
import com.chithira.fierydesk.impose.SheetLayout;

import java.awt.Dimension;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link SheetJob} into the PDF that is sent to the Fiery:
 * page 1 = front sheet (with crop marks when imposed), page 2 = back sheet.
 */
public final class SheetMaker {

    /**
     * The finished PDF and what it contains.
     *
     * @param copies         sheets to print (copies sent to the Fiery)
     * @param piecesPerSheet pieces on one sheet when imposed, otherwise 1
     */
    public record Sheets(byte[] pdf, int copies, int piecesPerSheet, String summary, Integer dpi, List<String> warnings) {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    private SheetMaker() {}

    /** The layout and numbers for a job, without building the PDF (used by the window's preview too). */
    public record Plan(SheetLayout layout, boolean fill, int dpi, int copies, String summary, String slug,
                       String problem) {
        public boolean ok() { return problem == null; }
    }

    /** Works out where everything goes. {@code problem} explains why it can't be printed, if so. */
    public static Plan plan(SheetJob j, Dimension px) {
        if (j.sheetW() <= 0 || j.sheetH() <= 0) {
            return new Plan(null, false, 0, 0, "Choose a paper size", null,
                    "Choose a paper with a known size for imposition / front and back.");
        }
        if (j.impose() != null) {
            ImposeSettings s = j.impose();
            SheetLayout layout = SheetLayout.impose(j.sheetW(), j.sheetH(), s);
            if (layout.overflow || !layout.fits()) {
                return new Plan(layout, s.fill(), 0, 0, "Margins too large for this paper", null,
                        "The margins (" + Preset_fmt(s.marginMm()) + " mm) leave no room on " + j.paperName()
                                + ". Make the margin smaller or choose a bigger paper.");
            }
            // resolution at the size the piece really prints
            double longIn = Math.max(layout.pieceW, layout.pieceH) / 72;
            int dpi = (int) Math.round(Math.max(px.width, px.height) / longIn);
            int copies = Math.max(1, layout.sheetsFor(Math.max(1, s.quantity())));
            int made = copies * layout.perSheet();
            String resized = layout.resized()
                    ? " · pieces " + (layout.scale < 1 ? "shrunk" : "enlarged") + " to " + layout.pieceSizeLabel()
                      + " (" + Math.round(layout.scale * 100) + "%)"
                    : "";
            String summary = s.quantity() <= 0
                    ? layout + resized + " · enter the quantity" + (j.duplex() ? " · front + back" : "")
                    : layout + resized + " · " + copies + " sheet" + (copies == 1 ? "" : "s") + " for " + s.quantity()
                      + (made > s.quantity() ? " (+" + (made - s.quantity()) + " extra)" : "")
                      + (j.duplex() ? " · front + back" : "");
            String slug = s.slug() ? j.front().getFileName() + " · " + layout + " · " + layout.pieceSizeLabel() + " · "
                    + copies + " sheets · " + LocalDateTime.now().format(STAMP) : null;
            return new Plan(layout, s.fill(), dpi, copies, summary, slug, null);
        }
        // one image per sheet, placed like a normal job (fit / fill / actual size)
        PageSpec spec = j.sizing() == Sizing.ACTUAL ? new PageSpec(0, 0, PageSpec.Fit.FIT, false)
                : new PageSpec(j.sheetW() / 72, j.sheetH() / 72, j.sizing() == Sizing.FILL ? PageSpec.Fit.FILL : PageSpec.Fit.FIT, true);
        PageSpec.Layout pl = spec.layout(px.width, px.height);
        double sw = j.sizing() == Sizing.ACTUAL ? pl.pageW() : j.sheetW();
        double sh = j.sizing() == Sizing.ACTUAL ? pl.pageH() : j.sheetH();
        SheetLayout layout = SheetLayout.single(sw, sh, pl.outW(), pl.outH(), pl.rotate());   // size already turned
        int copies = Math.max(1, j.copies());
        String summary = "1 per sheet" + (j.duplex() ? " · front + back" : "") + " · " + copies + " cop" + (copies == 1 ? "y" : "ies");
        return new Plan(layout, j.sizing() == Sizing.FILL, pl.dpi(), copies, summary, null, null);
    }

    public static Sheets make(SheetJob j, int minDpi, int warnDpi) throws IOException {
        Dimension px = DocumentNormalizer.imageSize(j.front())
                .orElseThrow(() -> new DocumentException("Cannot read the image " + j.front().getFileName()));
        if (j.back() != null && DocumentNormalizer.imageSize(j.back()).isEmpty()) {
            throw new DocumentException("Cannot read the back image " + j.back().getFileName());
        }
        Plan plan = plan(j, px);
        if (!plan.ok()) throw new DocumentException(plan.problem());
        if (plan.dpi() < minDpi && !j.allowLowRes()) {
            throw new DocumentException("Image is only " + plan.dpi() + " DPI at this size (minimum " + minDpi + "). It will print blurry.");
        }
        List<String> warnings = new ArrayList<>();
        if (plan.dpi() < warnDpi) warnings.add("Image is " + plan.dpi() + " DPI at this size.");

        byte[] pdf = SheetBuilder.build(plan.layout(),
                new SheetBuilder.Side(j.front(), "Front"),
                j.back() == null ? null : new SheetBuilder.Side(j.back(), "Back"),
                plan.fill(),
                j.impose() == null ? 0 : j.impose().backOffsetXmm() * ImposeSettings.PT_PER_MM,
                j.impose() == null ? 0 : j.impose().backOffsetYmm() * ImposeSettings.PT_PER_MM,
                plan.slug());
        String summary = plan.summary() + " on " + j.paperName();
        int piecesPerSheet = j.impose() == null ? 1 : plan.layout().perSheet();
        return new Sheets(pdf, plan.copies(), piecesPerSheet, summary, plan.dpi(), List.copyOf(warnings));
    }

    private static String Preset_fmt(double mm) {
        double r = Math.round(mm * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}
