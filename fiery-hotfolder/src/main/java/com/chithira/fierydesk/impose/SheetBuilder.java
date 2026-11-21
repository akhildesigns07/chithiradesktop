package com.chithira.fierydesk.impose;

import com.chithira.fierydesk.document.DocumentNormalizer;
import com.chithira.fierydesk.document.FileKind;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds the print-ready PDF: page 1 = front sheet, page 2 = back sheet (front/back jobs).
 * Each image is embedded once and placed in every cell, so the file stays small and the
 * pixels are untouched (JPEGs are embedded byte-for-byte).
 */
public final class SheetBuilder {

    /** What the sheet shows: artwork, how it fills a piece, marks and the info line. */
    public record Side(Path image, String label) {}

    private SheetBuilder() {}

    /**
     * @param back        null for single-sided
     * @param fill        cover each piece (crop if shapes differ) instead of fitting inside it
     * @param slugText    info line for the margin, or null for none ("Front"/"Back" is added)
     */
    public static byte[] build(SheetLayout layout, Side front, Side back, boolean fill,
                               double backOffsetX, double backOffsetY, String slugText) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDImageXObject frontImg = image(doc, front.image());
            page(doc, layout, layout.front, frontImg, fill, layout.cropMarks, slugText == null ? null : slugText + " · " + front.label());
            if (back != null) {
                PDImageXObject backImg = image(doc, back.image());
                // the back never gets crop marks (the cutter works from the front)
                page(doc, layout, layout.back(backOffsetX, backOffsetY), backImg, fill,
                        false, slugText == null ? null : slugText + " · " + back.label());
            }
            doc.getDocumentInformation().setCreator("Chithira Fiery Hot Folder");
            doc.getDocumentInformation().setTitle(front.image().getFileName().toString());
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void page(PDDocument doc, SheetLayout l, List<SheetLayout.Cell> cells, PDImageXObject img,
                             boolean fill, boolean marks, String slug) throws IOException {
        PDPage page = new PDPage(new PDRectangle((float) l.sheetW, (float) l.sheetH));
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            for (SheetLayout.Cell c : cells) drawPiece(cs, l, c, img, fill);
            if (marks) cropMarks(cs, l, cells);
            if (slug != null) slug(cs, l, slug);
        }
    }

    /** Artwork into one piece: clipped to its bleed box, turned as the cell says. */
    private static void drawPiece(PDPageContentStream cs, SheetLayout l, SheetLayout.Cell c,
                                  PDImageXObject img, boolean fill) throws IOException {
        double inner = Math.min(l.bleed, l.gap / 2);           // bleed between pieces may not overlap
        double bl = c.outerLeft() ? l.bleed : inner, br = c.outerRight() ? l.bleed : inner;
        double bt = c.outerTop() ? l.bleed : inner, bb = c.outerBottom() ? l.bleed : inner;

        cs.saveGraphicsState();
        cs.addRect((float) (c.x() - bl), (float) (c.y() - bb), (float) (c.w() + bl + br), (float) (c.h() + bb + bt));
        cs.clip();

        // artwork box in the piece's own (upright) orientation = trim + bleed on every side
        boolean turned = c.rotation() != 0 && c.rotation() % 180 != 0;
        double artW = (turned ? c.h() : c.w()) + 2 * l.bleed;
        double artH = (turned ? c.w() : c.h()) + 2 * l.bleed;
        double s = fill ? Math.max(artW / img.getWidth(), artH / img.getHeight())
                        : Math.min(artW / img.getWidth(), artH / img.getHeight());
        double dw = img.getWidth() * s, dh = img.getHeight() * s;

        cs.transform(Matrix.getTranslateInstance((float) (c.x() + c.w() / 2), (float) (c.y() + c.h() / 2)));
        if (c.rotation() != 0) cs.transform(Matrix.getRotateInstance(Math.toRadians(c.rotation()), 0, 0));
        cs.drawImage(img, (float) (-dw / 2), (float) (-dh / 2), (float) dw, (float) dh);
        cs.restoreGraphicsState();
    }

    /** Short cut marks outside the grid, in line with every cut. */
    private static void cropMarks(PDPageContentStream cs, SheetLayout l, List<SheetLayout.Cell> cells) throws IOException {
        double left = Double.MAX_VALUE, right = -Double.MAX_VALUE, bottom = Double.MAX_VALUE, top = -Double.MAX_VALUE;
        for (SheetLayout.Cell c : cells) {
            left = Math.min(left, c.x()); right = Math.max(right, c.x() + c.w());
            bottom = Math.min(bottom, c.y()); top = Math.max(top, c.y() + c.h());
        }
        cs.saveGraphicsState();
        cs.setLineWidth(0.3f);
        cs.setStrokingColor(0f, 0f, 0f, 1f);               // 100 % black (K)
        double o = l.markOffset, len = l.markLength;
        for (double x : l.cutXs(cells)) {
            line(cs, x, top + o, x, top + o + len);
            line(cs, x, bottom - o, x, bottom - o - len);
        }
        for (double y : l.cutYs(cells)) {
            line(cs, left - o, y, left - o - len, y);
            line(cs, right + o, y, right + o + len, y);
        }
        cs.stroke();
        cs.restoreGraphicsState();
    }

    private static void line(PDPageContentStream cs, double x1, double y1, double x2, double y2) throws IOException {
        cs.moveTo((float) x1, (float) y1);
        cs.lineTo((float) x2, (float) y2);
    }

    private static void slug(PDPageContentStream cs, SheetLayout l, String text) throws IOException {
        double space = l.gridY - (l.cropMarks ? l.markOffset + l.markLength : 0);
        if (space < 3 * ImposeSettings.PT_PER_MM) return;     // no room in the margin
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        String safe = winAnsi(text);
        float size = 6f;
        float width = font.getStringWidth(safe) / 1000 * size;
        cs.beginText();
        cs.setFont(font, size);
        cs.setNonStrokingColor(0f, 0f, 0f, 0.7f);
        cs.newLineAtOffset((float) Math.max(4, (l.sheetW - width) / 2), (float) Math.max(3, space / 2 - 2));
        cs.showText(safe);
        cs.endText();
    }

    /** The built-in font only knows Western characters; replace the rest. */
    private static String winAnsi(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) sb.append(ch < 0x7f || "×·–éèàáíóúñçäöüÉ".indexOf(ch) >= 0 ? ch : '?');
        return sb.toString();
    }

    /** JPEGs are embedded as they are (no quality loss, CMYK kept); other images losslessly. */
    static PDImageXObject image(PDDocument doc, Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (FileKind.detect(bytes) == FileKind.JPEG) {
            try {
                return JPEGFactory.createFromByteArray(doc, bytes);
            } catch (IOException | RuntimeException e) {
                // unusual JPEG: fall back to decoding it
            }
        }
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
        if (src == null) throw new IOException("Cannot read image " + file.getFileName());
        return LosslessFactory.createFromImage(doc, DocumentNormalizer.flattenOnWhite(src));
    }
}
