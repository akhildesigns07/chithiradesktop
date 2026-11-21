package com.chithira.fierydesk.driver;

import com.chithira.fierydesk.core.Sizing;
import com.chithira.fierydesk.document.DocumentNormalizer;
import com.chithira.fierydesk.document.PageSpec;

import javax.imageio.ImageIO;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Draws images onto printer pages, one image per page: what a photo viewer
 * does in step 3 of Ctrl+P. The driver then turns these pages into PostScript.
 *
 * Java may call print() several times for the same page (banding), so the
 * current image is cached instead of being re-read each time.
 */
final class ImagePages implements Printable {

    private final List<Path> files;
    private final Sizing sizing;

    private int cachedIndex = -1;
    private BufferedImage cached;

    ImagePages(List<Path> files, Sizing sizing) {
        this.files = files;
        this.sizing = sizing;
    }

    @Override
    public int print(Graphics g, PageFormat pf, int pageIndex) throws PrinterException {
        if (pageIndex >= files.size()) return NO_SUCH_PAGE;
        BufferedImage img = image(pageIndex);
        draw((Graphics2D) g, pf, img, sizing);
        return PAGE_EXISTS;
    }

    private BufferedImage image(int index) throws PrinterException {
        if (index != cachedIndex) {
            cached = null; // free the previous one first: print images can be large
            try {
                BufferedImage src = ImageIO.read(files.get(index).toFile());
                if (src == null) throw new PrinterException("Unsupported image: " + files.get(index).getFileName());
                // Transparency would make Java rasterise the whole page at low resolution
                cached = DocumentNormalizer.flattenOnWhite(src);
                cachedIndex = index;
            } catch (IOException e) {
                throw new PrinterException("Cannot read " + files.get(index).getFileName() + ": " + e.getMessage());
            }
        }
        return cached;
    }

    /** Fit / fill / actual size inside the printable area, auto-rotating like photo viewers do. */
    static void draw(Graphics2D g2, PageFormat pf, BufferedImage img, Sizing sizing) {
        double ax = pf.getImageableX(), ay = pf.getImageableY();
        double aw = pf.getImageableWidth(), ah = pf.getImageableHeight();

        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.clip(new Rectangle2D.Double(ax, ay, aw, ah));

        int w = img.getWidth(), h = img.getHeight();
        boolean rotate = sizing != Sizing.ACTUAL && w != h && ((w > h) != (aw > ah));
        double iw = rotate ? h : w;
        double ih = rotate ? w : h;

        double scale = switch (sizing) {
            case FILL -> Math.max(aw / iw, ah / ih);
            case ACTUAL -> PageSpec.POINTS_PER_INCH / PageSpec.ORIGINAL_DPI;   // 300 DPI = original size
            default -> Math.min(aw / iw, ah / ih);
        };

        AffineTransform t = new AffineTransform();
        t.translate(ax + (aw - iw * scale) / 2, ay + (ah - ih * scale) / 2);
        t.scale(scale, scale);
        if (rotate) {
            t.translate(iw, 0);
            t.rotate(Math.PI / 2);
        }
        g2.drawImage(img, t, null);
    }
}
