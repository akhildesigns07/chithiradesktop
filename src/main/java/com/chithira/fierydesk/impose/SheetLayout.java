package com.chithira.fierydesk.impose;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Where every piece sits on the sheet. Units are PDF points (1/72 in), origin
 * bottom-left, like a PDF page.
 *
 * The back side is the front mirrored left↔right (the sheet turns over its long,
 * vertical edge), so each back lands exactly behind its front. Pieces turned 90°
 * on the front are turned the other way on the back, so both read upright.
 */
public final class SheetLayout {

    /** One piece: its trim box and how its artwork is turned (degrees, counter-clockwise). */
    public record Cell(double x, double y, double w, double h, int rotation,
                       boolean outerLeft, boolean outerRight, boolean outerTop, boolean outerBottom) {}

    public final double sheetW, sheetH;
    public final double pieceW, pieceH;        // trim size on the sheet (after any turn)
    public final boolean rotated;
    public final int cols, rows;
    public final double bleed, gap;
    public final boolean cropMarks;
    public final double markOffset, markLength;
    public final double gridX, gridY, gridW, gridH;
    public final boolean overflow;             // forced rows/cols don't fit
    public final List<Cell> front;

    private SheetLayout(double sheetW, double sheetH, double pieceW, double pieceH, boolean rotated,
                        int cols, int rows, double bleed, double gap, boolean cropMarks, boolean overflow) {
        this.sheetW = sheetW;
        this.sheetH = sheetH;
        this.pieceW = pieceW;
        this.pieceH = pieceH;
        this.rotated = rotated;
        this.cols = cols;
        this.rows = rows;
        this.bleed = bleed;
        this.gap = gap;
        this.cropMarks = cropMarks;
        this.overflow = overflow;
        this.markOffset = Math.max(bleed, 2 * ImposeSettings.PT_PER_MM);
        this.markLength = 4 * ImposeSettings.PT_PER_MM;
        this.gridW = cols > 0 ? cols * pieceW + (cols - 1) * gap : 0;
        this.gridH = rows > 0 ? rows * pieceH + (rows - 1) * gap : 0;
        this.gridX = (sheetW - gridW) / 2;
        this.gridY = (sheetH - gridH) / 2;

        List<Cell> cells = new ArrayList<>();
        int rot = rotated ? 90 : 0;
        for (int r = 0; r < rows; r++) {          // r = 0 is the top row
            for (int c = 0; c < cols; c++) {
                double x = gridX + c * (pieceW + gap);
                double y = gridY + (rows - 1 - r) * (pieceH + gap);
                cells.add(new Cell(x, y, pieceW, pieceH, rot, c == 0, c == cols - 1, r == 0, r == rows - 1));
            }
        }
        this.front = List.copyOf(cells);
    }

    public int perSheet() { return cols * rows; }

    public boolean fits() { return perSheet() > 0; }

    /** Sheets needed for a quantity of pieces. */
    public int sheetsFor(int quantity) {
        return perSheet() == 0 ? 0 : (int) Math.ceil(quantity / (double) perSheet());
    }

    /** Back side: mirrored left↔right, opposite turn, optional press offset (pt). */
    public List<Cell> back(double offsetX, double offsetY) {
        List<Cell> out = new ArrayList<>();
        for (Cell c : front) {
            out.add(new Cell(sheetW - c.x() - c.w() + offsetX, c.y() + offsetY, c.w(), c.h(),
                    -c.rotation(), c.outerRight(), c.outerLeft(), c.outerTop(), c.outerBottom()));
        }
        return out;
    }

    /** x positions of every cut line (for crop marks), shared lines counted once. */
    public List<Double> cutXs(List<Cell> cells) {
        TreeSet<Double> xs = new TreeSet<>();
        for (Cell c : cells) { addRounded(xs, c.x()); addRounded(xs, c.x() + c.w()); }
        return new ArrayList<>(xs);
    }

    public List<Double> cutYs(List<Cell> cells) {
        TreeSet<Double> ys = new TreeSet<>();
        for (Cell c : cells) { addRounded(ys, c.y()); addRounded(ys, c.y() + c.h()); }
        return new ArrayList<>(ys);
    }

    private static void addRounded(TreeSet<Double> set, double v) {
        double r = Math.round(v * 100) / 100.0;
        Double near = set.floor(r + 0.05);
        if (near == null || Math.abs(near - r) > 0.05) set.add(r);
    }

    // ------------------------------------------------------------------ building

    /**
     * Step-and-repeat: as many pieces as fit, upright or turned 90° (whichever fits more),
     * grid centred on the sheet.
     */
    public static SheetLayout impose(double sheetW, double sheetH, ImposeSettings s) {
        double mm = ImposeSettings.PT_PER_MM;
        double w = s.item().widthMm() * mm, h = s.item().heightMm() * mm;
        double bleed = s.bleedMm() * mm, gap = s.gapMm() * mm, margin = s.marginMm() * mm;
        double markSpace = Math.max(bleed, 2 * mm) + 4 * mm;     // crop marks are mandatory
        double availW = sheetW - 2 * margin - 2 * markSpace;
        double availH = sheetH - 2 * margin - 2 * markSpace;

        int uc = count(availW, w, gap), ur = count(availH, h, gap);
        int rc = s.allowRotate() ? count(availW, h, gap) : 0, rr = s.allowRotate() ? count(availH, w, gap) : 0;
        boolean rotated = rc * rr > uc * ur;
        double pw = rotated ? h : w, ph = rotated ? w : h;
        int cols = rotated ? rc : uc, rows = rotated ? rr : ur;

        boolean overflow = false;
        if (s.forceCols() > 0 || s.forceRows() > 0) {
            int fc = s.forceCols() > 0 ? s.forceCols() : cols;
            int fr = s.forceRows() > 0 ? s.forceRows() : rows;
            // if the forced grid only fits the other way round, turn the pieces
            if ((fc > cols || fr > rows) && s.allowRotate()) {
                int oc = rotated ? uc : rc, or = rotated ? ur : rr;
                if (fc <= oc && fr <= or) {
                    rotated = !rotated;
                    pw = rotated ? h : w;
                    ph = rotated ? w : h;
                    cols = oc;
                    rows = or;
                }
            }
            overflow = fc > cols || fr > rows;
            cols = fc;
            rows = fr;
        }
        return new SheetLayout(sheetW, sheetH, pw, ph, rotated, cols, rows, bleed, gap, true, overflow);
    }

    /**
     * One piece per sheet (front/back without imposition): the piece box comes from the
     * normal page placement (fit / fill / actual size).
     */
    public static SheetLayout single(double sheetW, double sheetH, double pieceW, double pieceH, boolean rotated) {
        return new SheetLayout(sheetW, sheetH, pieceW, pieceH, rotated, 1, 1, 0, 0, false, false);
    }

    private static int count(double avail, double size, double gap) {
        if (size <= 0 || avail < size) return 0;
        return (int) Math.floor((avail + gap + 1e-6) / (size + gap));
    }

    @Override
    public String toString() {
        return perSheet() + "-up (" + cols + " × " + rows + ")" + (rotated ? ", turned 90°" : "");
    }
}
