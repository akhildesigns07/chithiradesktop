package com.chithira.fierydesk.document;

import com.chithira.fierydesk.impose.AutoSize;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.awt.Dimension;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The real page size of a print file, for "Actual size – page size = file size":
 * <ul>
 *   <li>images (JPG, PNG, TIFF …): pixels ÷ the DPI stored in the file, or {@value PageSpec#ORIGINAL_DPI} DPI when
 *       none is stored (72 / 96 are screen defaults and count as none);</li>
 *   <li>PDF: the first page's crop box (the visible page), turned when the page is rotated 90° / 270°;</li>
 *   <li>PostScript: the {@code %%HiResBoundingBox} or {@code %%BoundingBox} comment.</li>
 * </ul>
 * Sizes are kept in points (72 per inch).
 *
 * @param source how the size was found, e.g. "image at 600 DPI" or "PDF page"
 */
public record FilePageSize(double widthPt, double heightPt, String source) {

    /** Units the Fiery page-size attribute can be written in. */
    public enum Unit {
        IN(72.0), MM(72.0 / 25.4), PT(1.0);

        private final double pointsPerUnit;

        Unit(double pointsPerUnit) { this.pointsPerUnit = pointsPerUnit; }

        public static Unit parse(String text, Unit fallback) {
            try {
                return text == null || text.isBlank() ? fallback : valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /** How much of a PostScript file is searched for its bounding box. */
    private static final int POSTSCRIPT_HEADER_BYTES = 64 * 1024;
    private static final Pattern BOUNDING_BOX = Pattern.compile(
            "%%(?:HiRes)?BoundingBox:\\s*(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)");

    /** Measures a file on disk; empty when the type has no readable size. */
    public static Optional<FilePageSize> of(Path file) {
        try {
            FileKind kind = FileKind.detect(head(file, 4096));
            return switch (kind) {
                case PDF -> ofPdf(file);
                case POSTSCRIPT -> ofPostScript(file);
                case UNKNOWN -> Optional.empty();
                default -> ofImage(file);
            };
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** DPI an image is placed at for its actual size: the stored DPI, else {@value PageSpec#ORIGINAL_DPI}. */
    public static double imageDpi(Path image) {
        return AutoSize.storedDpi(image).orElse((double) PageSpec.ORIGINAL_DPI);
    }

    /** The size in a unit, e.g. {@code 3.5} (inches) — at most 2 decimals, no trailing zeros. */
    public String width(Unit unit) { return number(widthPt / unit.pointsPerUnit); }

    public String height(Unit unit) { return number(heightPt / unit.pointsPerUnit); }

    /**
     * Fills a template such as {@code {w}x{h}} → {@code 13x19}.
     * Placeholders: {@code {w}} and {@code {h}} in the given unit.
     */
    public String format(String template, Unit unit) {
        return template.replace("{w}", width(unit)).replace("{h}", height(unit));
    }

    /** For staff: "3.5 × 2 in (89 × 51 mm)". */
    public String label() {
        return width(Unit.IN) + " × " + height(Unit.IN) + " in (" + Math.round(widthPt / Unit.MM.pointsPerUnit) + " × "
                + Math.round(heightPt / Unit.MM.pointsPerUnit) + " mm)";
    }

    // ------------------------------------------------------------------ readers

    private static Optional<FilePageSize> ofImage(Path file) {
        Optional<Dimension> pixels = DocumentNormalizer.imageSize(file);
        if (pixels.isEmpty()) return Optional.empty();
        Optional<Double> stored = AutoSize.storedDpi(file);
        double dpi = stored.orElse((double) PageSpec.ORIGINAL_DPI);
        String source = stored.isPresent() ? "image at its " + Math.round(dpi) + " DPI"
                : "image at " + PageSpec.ORIGINAL_DPI + " DPI (no DPI stored in the file)";
        return Optional.of(new FilePageSize(pixels.get().width * 72.0 / dpi, pixels.get().height * 72.0 / dpi, source));
    }

    private static Optional<FilePageSize> ofPdf(Path file) {
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
            if (pdf.getNumberOfPages() == 0) return Optional.empty();
            PDPage page = pdf.getPage(0);
            PDRectangle box = page.getCropBox();
            boolean turned = page.getRotation() % 180 != 0;
            double width = turned ? box.getHeight() : box.getWidth();
            double height = turned ? box.getWidth() : box.getHeight();
            String source = "PDF page" + (pdf.getNumberOfPages() > 1 ? " 1 of " + pdf.getNumberOfPages() : "");
            return Optional.of(new FilePageSize(width, height, source));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<FilePageSize> ofPostScript(Path file) throws IOException {
        String header = new String(head(file, POSTSCRIPT_HEADER_BYTES), StandardCharsets.ISO_8859_1);
        Matcher hiRes = Pattern.compile("%%HiResBoundingBox:[^\\r\\n]*").matcher(header);
        Matcher box = BOUNDING_BOX.matcher(hiRes.find() ? hiRes.group() : header);
        if (!box.find()) return Optional.empty();
        double width = Double.parseDouble(box.group(3)) - Double.parseDouble(box.group(1));
        double height = Double.parseDouble(box.group(4)) - Double.parseDouble(box.group(2));
        return width > 0 && height > 0 ? Optional.of(new FilePageSize(width, height, "PostScript bounding box")) : Optional.empty();
    }

    private static byte[] head(Path file, int bytes) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(bytes);
        }
    }

    private static String number(double value) {
        double rounded = Math.round(value * 100) / 100.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded)
                : String.valueOf(rounded).replaceAll("0+$", "");
    }
}
