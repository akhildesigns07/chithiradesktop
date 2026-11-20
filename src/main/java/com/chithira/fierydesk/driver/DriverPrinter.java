package com.chithira.fierydesk.driver;

import com.chithira.fierydesk.document.FileKind;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.printing.PDFPageable;

import javax.print.PrintService;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.Copies;
import javax.print.attribute.standard.JobName;
import javax.print.attribute.standard.Media;
import javax.print.attribute.standard.MediaSize;
import javax.print.attribute.standard.MediaSizeName;
import java.awt.print.PageFormat;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Prints files exactly the way Ctrl+P does: Java draws each image onto a page,
 * the operating system spooler hands the pages to the installed printer driver
 * (the Fiery PostScript driver), and the driver sends the job to the printer
 * queue it is connected to (e.g. the Fiery Hold queue).
 *
 * Supports a batch: one job per file, or all files combined into one job.
 * PDFs are printed page by page through the same driver (like printing from a PDF viewer).
 */
public final class DriverPrinter {

    /** Result for one file (or for the whole batch when combined). */
    public record Outcome(Path file, boolean ok, String message) {}

    public interface Listener {
        void started(Path file);
        void finished(Outcome outcome);
    }

    private DriverPrinter() {}

    // ------------------------------------------------------------ discovery

    /** Printers installed on this computer, as shown in Ctrl+P. */
    public static List<PrintService> printers() {
        return Arrays.asList(PrinterJob.lookupPrintServices());
    }

    public static Optional<PrintService> find(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return printers().stream().filter(s -> s.getName().equalsIgnoreCase(name.trim())).findFirst();
    }

    /** Paper sizes the driver offers (including driver-specific ones such as "13x19"). */
    public static List<MediaSizeName> paperSizes(PrintService service) {
        List<MediaSizeName> out = new ArrayList<>();
        Object v = service.getSupportedAttributeValues(Media.class, null, null);
        if (v instanceof Media[] media) {
            for (Media m : media) if (m instanceof MediaSizeName n) out.add(n);
        }
        return out;
    }

    /** The driver's default paper, if it reports one. */
    public static Optional<MediaSizeName> defaultPaper(PrintService service) {
        Object d = service.getDefaultAttributeValue(Media.class);
        return d instanceof MediaSizeName n ? Optional.of(n) : Optional.empty();
    }

    /** Paper size in inches, if known: {width, height}. */
    public static Optional<double[]> inches(MediaSizeName paper) {
        if (paper == null) return Optional.empty();
        MediaSize ms = MediaSize.getMediaSizeForName(paper);
        if (ms == null) return Optional.empty();
        return Optional.of(new double[]{ms.getX(MediaSize.INCH), ms.getY(MediaSize.INCH)});
    }

    public static String label(MediaSizeName paper) {
        if (paper == null) return "Driver default";
        String name = paper.toString();
        MediaSize ms = MediaSize.getMediaSizeForName(paper);
        if (ms == null) return name;
        return name + "  (" + Math.round(ms.getX(MediaSize.MM)) + " × " + Math.round(ms.getY(MediaSize.MM)) + " mm)";
    }

    // ------------------------------------------------------------ printing

    /**
     * Creates the print job for the chosen printer. If options.showPrintDialog() is set,
     * shows the normal Windows print dialog (call on the Swing thread) and returns
     * empty if the user cancels.
     */
    public static Optional<PrinterJob> prepare(DriverPrintOptions o) throws PrinterException {
        PrintService service = find(o.printerName())
                .orElseThrow(() -> new PrinterException("Printer not installed: " + o.printerName()));
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setPrintService(service);
        if (o.showPrintDialog() && !job.printDialog()) return Optional.empty(); // native dialog, like Ctrl+P
        return Optional.of(job);
    }

    /**
     * Prints the batch. Run on a background thread. With the print dialog, the
     * settings chosen there (copies, paper, driver Properties) are used for every file.
     */
    public static void printBatch(PrinterJob job, List<Path> files, DriverPrintOptions o, Listener listener) {
        List<Path> images = new ArrayList<>();
        List<Path> pdfs = new ArrayList<>();
        for (Path f : files) {
            FileKind k = kindOf(f);
            if (k == FileKind.PDF) pdfs.add(f);
            else if (k == FileKind.POSTSCRIPT) {
                listener.finished(new Outcome(f, false,
                        "PostScript files can't be drawn by Java. Send them with the Fiery API method instead."));
            } else images.add(f);
        }

        if (o.combineIntoOneJob() && images.size() > 1) {
            images.forEach(listener::started);
            String name = "Batch of " + images.size() + " images";
            try {
                run(job, new ImagePages(images, o.sizing()), name, o);
                for (Path f : images) listener.finished(new Outcome(f, true, "Printed as page "
                        + (images.indexOf(f) + 1) + " of \"" + name + "\""));
            } catch (Exception e) {
                for (Path f : images) listener.finished(new Outcome(f, false, message(e)));
            }
        } else {
            for (Path f : images) {
                listener.started(f);
                try {
                    run(job, new ImagePages(List.of(f), o.sizing()), f.getFileName().toString(), o);
                    listener.finished(new Outcome(f, true, "Printed"));
                } catch (Exception e) {
                    listener.finished(new Outcome(f, false, message(e)));
                }
            }
        }

        for (Path f : pdfs) {
            listener.started(f);
            try (PDDocument doc = Loader.loadPDF(f.toFile())) {
                job.setPageable(new PDFPageable(doc));
                job.setJobName(f.getFileName().toString());
                job.print(attributes(o, f.getFileName().toString()));
                listener.finished(new Outcome(f, true, "Printed (PDF)"));
            } catch (Exception e) {
                listener.finished(new Outcome(f, false, message(e)));
            }
        }
    }

    private static void run(PrinterJob job, ImagePages pages, String jobName, DriverPrintOptions o) throws PrinterException {
        job.setJobName(jobName);
        if (o.showPrintDialog()) {
            // Settings came from the Windows dialog; don't override them
            job.setPrintable(pages);
            job.print();
        } else {
            PrintRequestAttributeSet attrs = attributes(o, jobName);
            PageFormat pf = job.getPageFormat(attrs);   // paper + printable area from the driver
            job.setPrintable(pages, pf);
            job.print(attrs);
        }
    }

    private static PrintRequestAttributeSet attributes(DriverPrintOptions o, String jobName) {
        PrintRequestAttributeSet a = new HashPrintRequestAttributeSet();
        if (o.showPrintDialog()) return a;
        a.add(new JobName(jobName, Locale.getDefault()));
        a.add(new Copies(Math.max(1, o.copies())));
        if (o.paper() != null) a.add(o.paper());
        return a;
    }

    private static FileKind kindOf(Path f) {
        try (InputStream in = Files.newInputStream(f)) {
            return FileKind.detect(in.readNBytes(8));
        } catch (IOException e) {
            return FileKind.UNKNOWN;
        }
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }
}
