package com.chithira.fierydesk.impose;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/** Quick picture of a sheet for the window: same geometry as the PDF, drawn with thumbnails. */
public final class SheetPreview {

    private SheetPreview() {}

    /**
     * @param thumb  small version of the artwork (null draws grey boxes)
     * @param title  caption drawn on top, e.g. "Front"
     */
    public static BufferedImage render(SheetLayout l, List<SheetLayout.Cell> cells, BufferedImage thumb,
                                       boolean fill, boolean marks, int maxW, int maxH, String title) {
        double scale = Math.min((maxW - 10) / l.sheetW, (maxH - 24) / l.sheetH);
        int w = (int) Math.round(l.sheetW * scale), h = (int) Math.round(l.sheetH * scale);
        BufferedImage img = new BufferedImage(w + 10, h + 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new Color(0xEE, 0xEF, 0xF2));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        if (title != null) {
            g.setColor(Color.DARK_GRAY);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.drawString(title, 5, 15);
        }

        // sheet: PDF y-up -> screen y-down
        AffineTransform toScreen = new AffineTransform();
        toScreen.translate(5, 19 + h);
        toScreen.scale(scale, -scale);

        g.setColor(new Color(0, 0, 0, 40));
        g.fillRect(7, 21, w, h);
        g.setColor(Color.WHITE);
        g.fillRect(5, 19, w, h);

        for (SheetLayout.Cell c : cells) {
            Shape box = toScreen.createTransformedShape(new Rectangle2D.Double(c.x(), c.y(), c.w(), c.h()));
            Graphics2D cg = (Graphics2D) g.create();
            cg.clip(box);
            if (thumb != null) {
                boolean turned = c.rotation() != 0 && c.rotation() % 180 != 0;
                double artW = turned ? c.h() : c.w(), artH = turned ? c.w() : c.h();
                double s = fill ? Math.max(artW / thumb.getWidth(), artH / thumb.getHeight())
                                : Math.min(artW / thumb.getWidth(), artH / thumb.getHeight());
                AffineTransform t = new AffineTransform(toScreen);
                t.translate(c.x() + c.w() / 2, c.y() + c.h() / 2);
                t.rotate(Math.toRadians(c.rotation()));
                t.scale(s, -s);                        // image rows go downwards
                t.translate(-thumb.getWidth() / 2.0, -thumb.getHeight() / 2.0);
                cg.drawImage(thumb, t, null);
            } else {
                cg.setColor(new Color(0xD0, 0xD7, 0xDE));
                cg.fill(box);
            }
            cg.dispose();
            g.setColor(new Color(0, 0, 0, 60));
            g.setStroke(new BasicStroke(0.5f));
            g.draw(box);
        }

        if (marks && !cells.isEmpty()) {
            double left = Double.MAX_VALUE, right = -Double.MAX_VALUE, bottom = Double.MAX_VALUE, top = -Double.MAX_VALUE;
            for (SheetLayout.Cell c : cells) {
                left = Math.min(left, c.x()); right = Math.max(right, c.x() + c.w());
                bottom = Math.min(bottom, c.y()); top = Math.max(top, c.y() + c.h());
            }
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(1f));
            double o = l.markOffset, len = l.markLength;
            for (double x : l.cutXs(cells)) {
                g.draw(toScreen.createTransformedShape(new Line2D.Double(x, top + o, x, top + o + len)));
                g.draw(toScreen.createTransformedShape(new Line2D.Double(x, bottom - o, x, bottom - o - len)));
            }
            for (double y : l.cutYs(cells)) {
                g.draw(toScreen.createTransformedShape(new Line2D.Double(left - o, y, left - o - len, y)));
                g.draw(toScreen.createTransformedShape(new Line2D.Double(right + o, y, right + o + len, y)));
            }
        }
        g.dispose();
        return img;
    }
}
