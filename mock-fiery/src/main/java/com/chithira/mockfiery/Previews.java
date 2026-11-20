package com.chithira.mockfiery;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Page images for the web page, so imposition and front/back can be checked by eye. */
final class Previews {

    private static final int LONG_SIDE_PX = 1000;

    private final Path cacheDir;

    Previews(Path dataDir) throws IOException {
        this.cacheDir = dataDir.resolve("previews");
        Files.createDirectories(cacheDir);
    }

    /** PNG of page (1-based). Null if no preview can be made. */
    byte[] page(JobStore.Job job, Path file, int page) {
        Path cached = cacheDir.resolve(job.id + "-" + page + ".png");
        try {
            if (Files.exists(cached)) return Files.readAllBytes(cached);
            BufferedImage img = switch (job.format) {
                case "PDF" -> pdf(file, page);
                case "TIFF" -> tiff(file, page);
                default -> ghostscript(file, page);
            };
            if (img == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            Files.write(cached, out.toByteArray());
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage pdf(Path file, int page) throws IOException {
        try (PDDocument d = Loader.loadPDF(file.toFile())) {
            if (page < 1 || page > d.getNumberOfPages()) return null;
            PDRectangle box = d.getPage(page - 1).getMediaBox();
            float dpi = (float) (LONG_SIDE_PX * 72.0 / Math.max(box.getWidth(), box.getHeight()));
            return new PDFRenderer(d).renderImageWithDPI(page - 1, dpi);
        }
    }

    private static BufferedImage tiff(Path file, int page) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) return null;
            ImageReader r = it.next();
            r.setInput(in);
            BufferedImage img = r.read(page - 1);
            r.dispose();
            return img;
        }
    }

    /** PostScript previews need Ghostscript installed (gswin64c on Windows, gs elsewhere). */
    private static BufferedImage ghostscript(Path file, int page) throws Exception {
        Path out = Files.createTempFile("mockfiery-", ".png");
        try {
            for (String exe : List.of("gswin64c", "gswin32c", "gs")) {
                try {
                    Process p = new ProcessBuilder(exe, "-q", "-dSAFER", "-dBATCH", "-dNOPAUSE",
                            "-sDEVICE=png16m", "-r40", "-dFirstPage=" + page, "-dLastPage=" + page,
                            "-sOutputFile=" + out.toAbsolutePath(), file.toAbsolutePath().toString())
                            .redirectErrorStream(true).start();
                    p.getInputStream().readAllBytes();
                    if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0 && Files.size(out) > 0) {
                        return ImageIO.read(out.toFile());
                    }
                } catch (IOException notInstalled) {
                    // try the next name
                }
            }
            return null;
        } finally {
            Files.deleteIfExists(out);
        }
    }
}
