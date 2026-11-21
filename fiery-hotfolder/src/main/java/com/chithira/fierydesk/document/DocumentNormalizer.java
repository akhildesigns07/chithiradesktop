package com.chithira.fierydesk.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Makes any customer file print-ready for the Fiery.
 * PDF / PostScript / TIFF pass through. Images are placed on a PDF page.
 */
public final class DocumentNormalizer {

    private final int minDpi;
    private final int warnDpi;

    public DocumentNormalizer(int minDpi, int warnDpi) {
        this.minDpi = minDpi;
        this.warnDpi = warnDpi;
    }

    public PreparedDocument prepare(byte[] bytes, String jobName, PageSpec page, boolean allowLowRes) {
        FileKind kind = FileKind.detect(bytes);
        String base = safeName(jobName);
        return switch (kind) {
            case PDF -> new PreparedDocument(bytes, base + ".pdf", "application/pdf", kind, null, List.of());
            case POSTSCRIPT -> new PreparedDocument(bytes, base + ".ps", "application/postscript", kind, null, List.of());
            case TIFF -> new PreparedDocument(bytes, base + ".tif", "image/tiff", kind, null, List.of());
            case UNKNOWN -> throw new DocumentException("The file is empty or unreadable.");
            default -> imageToPdf(bytes, base, kind, page, allowLowRes);
        };
    }

    // ------------------------------------------------------------ image -> PDF

    private PreparedDocument imageToPdf(byte[] bytes, String base, FileKind kind, PageSpec page, boolean allowLowRes) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDImageXObject image = toPdfImage(doc, bytes, kind);
            PageSpec.Layout l = page.layout(image.getWidth(), image.getHeight());

            List<String> warnings = new ArrayList<>();
            if (l.dpi() < minDpi) {
                if (!allowLowRes) throw new DocumentException("Image is only " + l.dpi()
                        + " DPI at this size (minimum " + minDpi + "). It will print blurry.");
                warnings.add("Sent at low resolution (" + l.dpi() + " DPI) on request.");
            } else if (l.dpi() < warnDpi) {
                warnings.add("Image is " + l.dpi() + " DPI; may look slightly soft.");
            }
            if (l.cropped()) warnings.add("Edges cropped to fill the page.");
            if (l.margins()) warnings.add("White margins (image proportions differ from page).");

            PDPage pdPage = new PDPage(new PDRectangle(l.pageW(), l.pageH()));
            doc.addPage(pdPage);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
                cs.addRect(0, 0, l.pageW(), l.pageH());
                cs.clip();
                if (l.rotate()) {
                    // 90° counter-clockwise: image's width runs up the page
                    cs.transform(new Matrix(0, 1, -1, 0, l.x() + l.outW(), l.y()));
                    cs.drawImage(image, 0, 0, l.outH(), l.outW());
                } else {
                    cs.drawImage(image, l.x(), l.y(), l.outW(), l.outH());
                }
            }
            doc.getDocumentInformation().setCreator("Chithira Fiery Hot Folder");
            doc.save(out);
            return new PreparedDocument(out.toByteArray(), base + ".pdf", "application/pdf",
                    kind, l.dpi(), List.copyOf(warnings));
        } catch (IOException e) {
            throw new DocumentException("Could not read the image: " + e.getMessage());
        }
    }

    private static PDImageXObject toPdfImage(PDDocument doc, byte[] bytes, FileKind kind) throws IOException {
        if (kind == FileKind.JPEG) {
            // Original JPEG data embedded: no quality loss, CMYK JPEGs stay CMYK
            return JPEGFactory.createFromByteArray(doc, bytes);
        }
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
        if (src == null) throw new DocumentException(
                "Unsupported file type. Use PDF, JPG, PNG, TIFF, WebP, BMP or PSD.");
        return LosslessFactory.createFromImage(doc, flattenOnWhite(src));
    }

    /** Transparent areas would otherwise print unpredictably (often black). */
    public static BufferedImage flattenOnWhite(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }

    // ------------------------------------------------------ helpers for the popup

    /** Pixel size of an image without decoding it fully. Empty for PDF/PS or unreadable files. */
    public static Optional<Dimension> imageSize(Path file) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) return Optional.empty();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return Optional.empty();
            ImageReader r = readers.next();
            try {
                r.setInput(in, true, true);
                return Optional.of(new Dimension(r.getWidth(0), r.getHeight(0)));
            } finally {
                r.dispose();
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Small preview image; memory-friendly (subsampled) for huge print files. */
    public static Optional<BufferedImage> thumbnail(Path file, FileKind kind, int maxSide) {
        try {
            if (kind == FileKind.PDF) {
                try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
                    PDRectangle box = pdf.getPage(0).getMediaBox();
                    float dpi = (float) (maxSide * 72.0 / Math.max(box.getWidth(), box.getHeight()));
                    return Optional.of(new PDFRenderer(pdf).renderImageWithDPI(0, dpi));
                }
            }
            if (kind == FileKind.POSTSCRIPT) return Optional.empty();
            try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
                if (in == null) return Optional.empty();
                Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
                if (!readers.hasNext()) return Optional.empty();
                ImageReader r = readers.next();
                try {
                    r.setInput(in, true, true);
                    int step = Math.max(1, Math.max(r.getWidth(0), r.getHeight(0)) / maxSide);
                    ImageReadParam p = r.getDefaultReadParam();
                    p.setSourceSubsampling(step, step, 0, 0);
                    return Optional.of(r.read(0, p));
                } finally {
                    r.dispose();
                }
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static String safeName(String name) {
        if (name == null || name.isBlank()) return "job";
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return n.isEmpty() ? "job" : n;
    }
}
