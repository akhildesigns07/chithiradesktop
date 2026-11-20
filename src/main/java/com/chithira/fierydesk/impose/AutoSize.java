package com.chithira.fierydesk.impose;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Guesses the finished size of a customer's image, so staff don't need Photoshop:
 *  1. the DPI stored in the file, when it is a print resolution (150–1200 DPI)
 *  2. otherwise the standard size whose shape matches the image (card, A6, 4×6 …),
 *     choosing the one that gives a resolution closest to 300 DPI
 *  3. otherwise the image size at 300 DPI
 */
public final class AutoSize {

    /** The guess and why it was made (shown to staff). */
    public record Guess(Preset size, String reason) {}

    private static final double SHAPE_TOLERANCE = 0.03;

    private AutoSize() {}

    public static Guess guess(Path image, Dimension px, List<Preset> presets) {
        boolean landscape = px.width >= px.height;
        double ratio = (double) Math.max(px.width, px.height) / Math.min(px.width, px.height);

        // 1. DPI stored in the image
        Optional<Double> dpi = storedDpi(image);
        if (dpi.isPresent() && dpi.get() >= 150 && dpi.get() <= 1200) {
            double w = px.width / dpi.get() * 25.4, h = px.height / dpi.get() * 25.4;
            for (Preset p : presets) {
                Preset o = p.oriented(landscape);
                if (Math.abs(o.widthMm() - w) <= 2 && Math.abs(o.heightMm() - h) <= 2) {
                    return new Guess(o, "matches " + p.name() + " at the image's " + Math.round(dpi.get()) + " DPI");
                }
            }
            return new Guess(new Preset("Image size", w, h), "from the image's own " + Math.round(dpi.get()) + " DPI");
        }

        // 2. standard size with the same shape
        Preset best = null;
        double bestScore = Double.MAX_VALUE;
        for (Preset p : presets) {
            double pr = Math.max(p.widthMm(), p.heightMm()) / Math.min(p.widthMm(), p.heightMm());
            if (Math.abs(ratio - pr) / pr > SHAPE_TOLERANCE) continue;
            double eff = Math.max(px.width, px.height) / (Math.max(p.widthMm(), p.heightMm()) / 25.4);
            double score = Math.abs(eff - 300) + (eff < 150 ? 1000 : 0);   // prefer sharp sizes near 300 DPI
            if (score < bestScore) { bestScore = score; best = p; }
        }
        if (best != null) {
            return new Guess(best.oriented(landscape), "shape matches " + best.name());
        }

        // 3. image size at 300 DPI
        return new Guess(new Preset("Image size", px.width / 300.0 * 25.4, px.height / 300.0 * 25.4),
                "no size stored and no standard shape matched – image at 300 DPI");
    }

    /** DPI from the image file's metadata (JPEG JFIF/EXIF, PNG pHYs, TIFF …). */
    public static Optional<Double> storedDpi(Path image) {
        try (ImageInputStream in = ImageIO.createImageInputStream(image.toFile())) {
            if (in == null) return Optional.empty();
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) return Optional.empty();
            ImageReader r = it.next();
            try {
                r.setInput(in, true, false);
                IIOMetadata md = r.getImageMetadata(0);
                if (md == null || !md.isStandardMetadataFormatSupported()) return Optional.empty();
                Node node = find(md.getAsTree("javax_imageio_1.0"), "HorizontalPixelSize");
                if (node == null) return Optional.empty();
                NamedNodeMap a = node.getAttributes();
                double mmPerPixel = Double.parseDouble(a.getNamedItem("value").getNodeValue());
                if (mmPerPixel <= 0) return Optional.empty();
                double dpi = 25.4 / mmPerPixel;
                // 72/96 are screen defaults that say nothing about print size
                if (Math.abs(dpi - 72) < 0.5 || Math.abs(dpi - 96) < 0.5) return Optional.empty();
                return Optional.of(dpi);
            } finally {
                r.dispose();
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Node find(Node n, String name) {
        if (n == null) return null;
        if (name.equals(n.getNodeName())) return n;
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            Node f = find(c, name);
            if (f != null) return f;
        }
        return null;
    }
}
