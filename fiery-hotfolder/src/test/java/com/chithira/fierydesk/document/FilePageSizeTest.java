package com.chithira.fierydesk.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading a file's real page size for "Actual size – page size = file size". */
class FilePageSizeTest {

    @TempDir
    Path folder;

    @Test
    void imageWithoutStoredDpiIsMeasuredAt300Dpi() throws Exception {
        Path card = folder.resolve("card.png");
        ImageIO.write(new BufferedImage(1050, 600, BufferedImage.TYPE_INT_RGB), "png", card.toFile());

        FilePageSize size = FilePageSize.of(card).orElseThrow();

        assertEquals("3.5x2", size.format("{w}x{h}", FilePageSize.Unit.IN));
        assertEquals("3.5 × 2 in (89 × 51 mm)", size.label());
        assertTrue(size.source().contains("no DPI stored"));
    }

    @Test
    void imageUsesTheDpiStoredInTheFile() throws Exception {
        Path card = folder.resolve("card600.png");
        writePngWithDpi(new BufferedImage(2100, 1200, BufferedImage.TYPE_INT_RGB), 600, card);

        FilePageSize size = FilePageSize.of(card).orElseThrow();

        assertEquals("3.5x2", size.format("{w}x{h}", FilePageSize.Unit.IN));
        assertTrue(size.source().contains("600 DPI"), size.source());
        assertEquals(2100 * 72.0 / 600, PageSpec.actualSize(FilePageSize.imageDpi(card)).layout(2100, 1200).pageW(), 0.5);
    }

    @Test
    void pdfUsesItsFirstPageTurnedWhenRotated() throws Exception {
        Path upright = folder.resolve("poster.pdf"), turned = folder.resolve("turned.pdf");
        writePdf(upright, 936, 1368, 0);
        writePdf(turned, 936, 1368, 90);

        assertEquals("13x19", FilePageSize.of(upright).orElseThrow().format("{w}x{h}", FilePageSize.Unit.IN));
        assertEquals("19x13", FilePageSize.of(turned).orElseThrow().format("{w}x{h}", FilePageSize.Unit.IN));
        assertEquals("330.2 x 482.6", FilePageSize.of(upright).orElseThrow().format("{w} x {h}", FilePageSize.Unit.MM));
    }

    @Test
    void postScriptUsesItsBoundingBox() throws Exception {
        Path sticker = folder.resolve("sticker.ps");
        Files.writeString(sticker, "%!PS-Adobe-3.0\n%%BoundingBox: 0 0 252 144\n%%HiResBoundingBox: 0 0 252 144\nshowpage\n",
                StandardCharsets.ISO_8859_1);

        assertEquals("252x144", FilePageSize.of(sticker).orElseThrow().format("{w}x{h}", FilePageSize.Unit.PT));
    }

    @Test
    void unreadableFileHasNoSize() throws Exception {
        Path empty = folder.resolve("empty.jpg");
        Files.write(empty, new byte[0]);

        assertTrue(FilePageSize.of(empty).isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    static void writePdf(Path file, float widthPt, float heightPt, int rotation) throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(widthPt, heightPt));
            page.setRotation(rotation);
            pdf.addPage(page);
            pdf.save(file.toFile());
        }
    }

    private static void writePngWithDpi(BufferedImage image, double dpi, Path file) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null);
        // the PNG pHYs chunk, as design software writes it (pixels per metre)
        IIOMetadataNode pHYs = new IIOMetadataNode("pHYs");
        String pixelsPerMeter = String.valueOf(Math.round(dpi / 0.0254));
        pHYs.setAttribute("pixelsPerUnitXAxis", pixelsPerMeter);
        pHYs.setAttribute("pixelsPerUnitYAxis", pixelsPerMeter);
        pHYs.setAttribute("unitSpecifier", "meter");
        IIOMetadataNode root = new IIOMetadataNode("javax_imageio_png_1.0");
        root.appendChild(pHYs);
        metadata.mergeTree("javax_imageio_png_1.0", root);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(out);
            writer.write(new IIOImage(image, null, metadata));
        } finally {
            writer.dispose();
        }
    }
}
