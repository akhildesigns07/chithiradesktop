package com.chithira.fierydesk.driver;

import com.chithira.fierydesk.core.Sizing;
import com.chithira.fierydesk.document.FileKind;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.printing.PDFPageable;

import javax.print.StreamPrintServiceFactory;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.Copies;
import javax.print.attribute.standard.Destination;
import javax.print.attribute.standard.JobName;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.PrinterJob;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Converts a file to PostScript the way Ctrl+P does, but writes the result to a
 * file instead of printing it (Windows "Print to file"):
 *
 *   image / PDF ─▶ Java draws the pages ─▶ Fiery printer driver ─▶ PostScript bytes
 *
 * The bytes are then uploaded with the Fiery API. If no Fiery driver is installed,
 * {@link #BUILT_IN} uses Java's own PostScript writer instead (generic PostScript,
 * without Fiery driver features).
 */
public final class PostScriptConverter {

    /** Choice in the "Convert with" list when no printer driver should be used. */
    public static final String BUILT_IN = "Java built-in PostScript (no driver)";

    private PostScriptConverter() {}

    /**
     * @param converter  Windows printer whose driver does the conversion, or {@link #BUILT_IN}
     * @param pageWidthPt / pageHeightPt  paper size in points (from the Paper Catalog); 0 = default page
     * @param jobName    title written into the PostScript (the original file name)
     */
    public static byte[] convert(Path file, FileKind kind, String converter, double pageWidthPt, double pageHeightPt,
                                 Sizing sizing, String jobName) throws Exception {
        Path out = Files.createTempFile("fiery-", ".ps");
        OutputStream stream = null;
        try {
            PrinterJob job = PrinterJob.getPrinterJob();
            job.setJobName(jobName);
            PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();
            attrs.add(new Copies(1));                         // copies are sent through the API instead
            attrs.add(new JobName(jobName, Locale.getDefault()));

            if (converter == null || BUILT_IN.equals(converter)) {
                StreamPrintServiceFactory[] f = PrinterJob.lookupStreamPrintServices("application/postscript");
                if (f.length == 0) throw new IOException("Java's PostScript writer is not available");
                stream = Files.newOutputStream(out);
                job.setPrintService(f[0].getPrintService(stream));
            } else {
                job.setPrintService(DriverPrinter.find(converter)
                        .orElseThrow(() -> new IOException("Printer not installed: " + converter)));
                attrs.add(new Destination(out.toUri()));       // "Print to file": nothing is printed
            }

            if (kind == FileKind.PDF) {
                try (PDDocument doc = Loader.loadPDF(file.toFile())) {
                    job.setPageable(new PDFPageable(doc));
                    job.print(attrs);
                }
            } else {
                job.setPrintable(new ImagePages(List.of(file), sizing), pageFormat(job, pageWidthPt, pageHeightPt));
                job.print(attrs);
            }
            if (stream != null) { stream.close(); stream = null; }

            byte[] ps = Files.readAllBytes(out);
            if (ps.length == 0) {
                throw new IOException("The printer driver produced no output. Check that '" + converter
                        + "' is a PostScript printer that can print to a file.");
            }
            return ps;
        } finally {
            if (stream != null) try { stream.close(); } catch (IOException ignored) {}
            Files.deleteIfExists(out);
        }
    }

    /** True if the bytes look like PostScript (optionally wrapped in a PJL header, as Fiery drivers do). */
    public static boolean looksLikePostScript(byte[] b) {
        String head = new String(b, 0, Math.min(b.length, 2048), java.nio.charset.StandardCharsets.ISO_8859_1);
        return head.startsWith("%!") || head.contains("\n%!") || head.contains("@PJL");
    }

    private static PageFormat pageFormat(PrinterJob job, double w, double h) {
        PageFormat pf = job.defaultPage();
        if (w > 0 && h > 0) {
            Paper paper = new Paper();
            paper.setSize(w, h);
            paper.setImageableArea(0, 0, w, h);
            pf.setPaper(paper);
            pf.setOrientation(PageFormat.PORTRAIT);
            pf = job.validatePage(pf);   // a driver snaps this to its closest paper size (e.g. its 13x19)
        }
        return pf;
    }
}
