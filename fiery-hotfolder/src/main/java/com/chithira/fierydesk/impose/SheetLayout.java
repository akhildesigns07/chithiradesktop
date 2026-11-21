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
    public final boolean overflow;             // nothing fits at all (margins bigger than the sheet)
    public final double scale;                 // 1 = real piece size; < 1 shrunk, > 1 enlarged to fit
    public final List<Cell> front;

    private SheetLayout(double sheetW, double sheetH, double pieceW, double pieceH, boolean rotated,
                        int cols, int rows, double bleed, double gap, boolean cropMarks, boolean overflow, double scale) {
        this.scale = scale;
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
        if (availW <= 0 || availH <= 0 || w <= 0 || h <= 0) {
            return new SheetLayout(sheetW, sheetH, w, h, false, 0, 0, bleed, gap, true, true, 1);
        }

        // as many as fit at the real piece size, upright or turned
        int uc = count(availW, w, gap), ur = count(availH, h, gap);
        int rc = s.allowRotate() ? count(availW, h, gap) : 0, rr = s.allowRotate() ? count(availH, w, gap) : 0;
        boolean rotated = rc * rr > uc * ur;
        int cols = rotated ? rc : uc, rows = rotated ? rr : ur;

        boolean forced = s.forceCols() > 0 || s.forceRows() > 0;
        int wantC = s.forceCols() > 0 ? s.forceCols() : Math.max(1, cols);
        int wantR = s.forceRows() > 0 ? s.forceRows() : Math.max(1, rows);

        if (s.mode() == ImposeSettings.Mode.FIT_GRID) {
            if (s.forceCols() > 0 && s.forceRows() == 0) {
                wantR = Math.max(fillCount(availW, availH, w, h, s.forceCols(), gap),
                        s.allowRotate() ? fillCount(availW, availH, h, w, s.forceCols(), gap) : 0);
            } else if (s.forceRows() > 0 && s.forceCols() == 0) {
                wantC = Math.max(fillCount(availH, availW, h, w, s.forceRows(), gap),
                        s.allowRotate() ? fillCount(availH, availW, w, h, s.forceRows(), gap) : 0);
            }
            // resize (up or down, keeping proportions) so wantC × wantR fills the usable area;
            // nothing given = as many as fit at the real size, then resized to fill
            return scaled(sheetW, sheetH, w, h, Math.max(1, wantC), Math.max(1, wantR),
                    availW, availH, bleed, gap, s.allowRotate(), Double.MAX_VALUE);
        }
        if (forced) {
            // the grid asked for: real size if it fits, otherwise shrunk until it does
            boolean fitsUpright = wantC <= uc && wantR <= ur;
            boolean fitsTurned = s.allowRotate() && wantC <= rc && wantR <= rr;
            if (fitsUpright || fitsTurned) {
                boolean rot = !fitsUpright;
                return new SheetLayout(sheetW, sheetH, rot ? h : w, rot ? w : h, rot, wantC, wantR, bleed, gap, true, false, 1);
            }
            return scaled(sheetW, sheetH, w, h, wantC, wantR, availW, availH, bleed, gap, s.allowRotate(), 1);
        }
        if (cols * rows == 0) {
            // even one piece is bigger than the paper: shrink it to fit, centred
            return scaled(sheetW, sheetH, w, h, 1, 1, availW, availH, bleed, gap, s.allowRotate(), 1);
        }
        double pw = rotated ? h : w, ph = rotated ? w : h;
        return new SheetLayout(sheetW, sheetH, pw, ph, rotated, cols, rows, bleed, gap, true, false, 1);
    }

    /**
     * cols × rows pieces, resized (keeping proportions) to the largest size that fits;
     * upright or turned, whichever gives bigger pieces. {@code maxScale} = 1 means shrink only.
     */
    private static SheetLayout scaled(double sheetW, double sheetH, double w, double h, int cols, int rows,
                                      double availW, double availH, double bleed, double gap,
                                      boolean allowRotate, double maxScale) {
        double up = fitScale(w, h, cols, rows, availW, availH, gap);
        double turned = allowRotate ? fitScale(h, w, cols, rows, availW, availH, gap) : 0;
        boolean rot = turned > up;
        double s = Math.min(maxScale, rot ? turned : up);
        if (s <= 0) return new SheetLayout(sheetW, sheetH, w, h, false, 0, 0, bleed, gap, true, true, 1);
        double pw = (rot ? h : w) * s, ph = (rot ? w : h) * s;
        return new SheetLayout(sheetW, sheetH, pw, ph, rot, cols, rows, bleed, gap, true, false, s);
    }

    /**
     * With {@code fixed} pieces across {@code availA} sized to fill it, how many pieces fit
     * along {@code availB} (at least 1). {@code a} is the piece side along A, {@code b} along B.
     */
    private static int fillCount(double availA, double availB, double a, double b, int fixed, double gap) {
        double s = (availA - (fixed - 1) * gap) / (fixed * a);
        if (s <= 0) return 1;
        return Math.max(1, count(availB, b * s, gap));
    }

    private static double fitScale(double w, double h, int cols, int rows, double availW, double availH, double gap) {
        double sw = (availW - (cols - 1) * gap) / (cols * w);
        double sh = (availH - (rows - 1) * gap) / (rows * h);
        return Math.max(0, Math.min(sw, sh));
    }

    /**
     * One piece per sheet (front/back without imposition): the piece box comes from the
     * normal page placement (fit / fill / actual size).
     */
    public static SheetLayout single(double sheetW, double sheetH, double pieceW, double pieceH, boolean rotated) {
        return new SheetLayout(sheetW, sheetH, pieceW, pieceH, rotated, 1, 1, 0, 0, false, false, 1);
    }

    private static int count(double avail, double size, double gap) {
        if (size <= 0 || avail < size) return 0;
        return (int) Math.floor((avail + gap + 1e-6) / (size + gap));
    }

    /** True if the pieces were resized to fit (see {@link #scale}). */
    public boolean resized() { return Math.abs(scale - 1) > 0.001; }

    /** Piece size on the sheet in mm, e.g. "77 × 48 mm". */
    public String pieceSizeLabel() {
        double mm = ImposeSettings.PT_PER_MM;
        double a = rotated ? pieceH : pieceW, b = rotated ? pieceW : pieceH;
        return Math.round(a / mm) + " × " + Math.round(b / mm) + " mm";
    }

    @Override
    public String toString() {
        return perSheet() + "-up (" + cols + " × " + rows + ")" + (rotated ? ", turned 90°" : "");
    }
}
