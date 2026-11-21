package com.chithira.fierydesk.core;

import com.chithira.fierydesk.document.DocumentException;
import com.chithira.fierydesk.document.DocumentNormalizer;
import com.chithira.fierydesk.document.FileKind;
import com.chithira.fierydesk.document.PreparedDocument;
import com.chithira.fierydesk.fiery.FieryClient;
import com.chithira.fierydesk.fiery.FieryException;
import com.chithira.fierydesk.fiery.JobOptions;
import com.chithira.fierydesk.driver.PostScriptConverter;

import java.awt.Dimension;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One file: convert (if needed) -> upload to Fiery Hold queue -> log the response ->
 * delete the file (or move it to Sent/). On failure the file goes to Failed/.
 */
public final class JobProcessor {

    /**
     * What happened to one sent job.
     *
     * @param copiesSent     copies the Fiery was asked for (sheets when imposed)
     * @param piecesPerSheet pieces per sheet when imposed, otherwise null
     */
    public record Result(String jobId, String submittedAs, Integer dpi, List<String> warnings,
                         String fileAction, String rawResponse, int copiesSent, Integer piecesPerSheet) {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final FieryClient fiery;
    private final DocumentNormalizer normalizer;
    private final AppConfig.ImageMode imageMode;
    private final AppConfig.AfterSend afterSend;
    private final boolean useOriginalFileName;
    private final int minDpi;
    private final int warnDpi;
    private final String duplexAttributeName;
    private final String duplexAttributeValue;

    public JobProcessor(FieryClient fiery, AppConfig cfg) {
        this.fiery = fiery;
        this.normalizer = new DocumentNormalizer(cfg.minDpi, cfg.warnDpi);
        this.minDpi = cfg.minDpi;
        this.warnDpi = cfg.warnDpi;
        this.duplexAttributeName = cfg.duplexAttributeName;
        this.duplexAttributeValue = cfg.duplexAttributeValue;
        this.imageMode = cfg.imageMode;
        this.afterSend = cfg.afterSend;
        this.useOriginalFileName = cfg.useOriginalFileName;
    }

    public Result process(JobRequest r) throws Exception {
        long size = sizeOf(r.file()) + (r.back() == null ? 0 : sizeOf(r.back()));
        try {
            PreparedDocument doc;
            int copies = r.copies();
            String sheetInfo = null;
            Integer piecesPerSheet = null;
            if (r.sheet() != null) {
                // imposition and/or front + back: always made into a PDF first
                SheetMaker.Sheets sheets = SheetMaker.make(r.sheet(), minDpi, warnDpi);
                copies = sheets.copies();
                piecesPerSheet = r.sheet().impose() == null ? null : sheets.piecesPerSheet();
                sheetInfo = sheets.summary();
                doc = new PreparedDocument(sheets.pdf(), DocumentNormalizer.safeName(r.jobName()) + ".pdf",
                        "application/pdf", FileKind.PDF, sheets.dpi(), sheets.warnings());
                if (r.converter() != null) doc = sheetToPostScript(doc, r);
            } else {
                doc = prepare(Files.readAllBytes(r.file()), r);
            }

            JobOptions o = new JobOptions()
                    .queue(AppConfig.QUEUE)
                    .pcmid(r.paper() == null ? null : r.paper().id())
                    .copies(copies)
                    .instructions(r.instructions());
            if (r.sheet() != null && r.sheet().duplex() && duplexAttributeName != null && !duplexAttributeName.isBlank()) {
                o.attribute(duplexAttributeName, duplexAttributeValue);
            }

            // Like Ctrl+P: the Fiery shows the original name (e.g. photo.jpg); the content is PDF/PostScript
            String uploadName = useOriginalFileName ? originalUploadName(r) : doc.filename();
            FieryClient.UploadResult up = fiery.uploadJob(doc.content(), uploadName, doc.contentType(), o);

            // Only after the Fiery accepted the job: remove the file from the hot folder
            String action = afterSuccess(r.file());
            if (r.back() != null) action += "; back: " + afterSuccess(r.back());
            String submitted = uploadName + " (" + describe(doc, r) + (sheetInfo == null ? "" : ", " + sheetInfo) + ")";
            JobLog.sent(r, size, submitted, doc.effectiveDpi(), up.jobId(), action, up.rawResponse());
            return new Result(up.jobId(), submitted, doc.effectiveDpi(), doc.warnings(), action, up.rawResponse(),
                    copies, piecesPerSheet);

        } catch (Exception e) {
            String response = e instanceof FieryException fe ? fe.responseBody() : null;
            Path moved = moveToFailed(r.file(), e);
            if (r.back() != null) moveToFailed(r.back(), e);
            JobLog.failed(r, size, e.getMessage(), response,
                    moved.equals(r.file()) ? "left in hot folder" : "moved to " + moved + (r.back() == null ? "" : " (with its back)"));
            throw e;
        }
    }

    /** Original file name with extension; a job name typed in the window keeps the original extension. */
    static String originalUploadName(JobRequest r) {
        String original = r.file().getFileName().toString();
        if (r.jobName() == null || r.jobName().isBlank()
                || r.jobName().equals(DocumentNormalizer.safeName(original))) return original;
        int dot = original.lastIndexOf('.');
        String ext = dot > 0 ? original.substring(dot) : "";
        return r.jobName().endsWith(ext) ? r.jobName() : r.jobName() + ext;
    }

    private static String describe(PreparedDocument doc, JobRequest r) {
        String content = switch (doc.contentType()) {
            case "application/postscript" -> "PostScript";
            case "application/pdf" -> "PDF";
            default -> doc.contentType();
        };
        if (r.converter() != null && doc.sourceKind() != FileKind.POSTSCRIPT) {
            return content + " made by " + (PostScriptConverter.BUILT_IN.equals(r.converter()) ? "Java" : r.converter() + " driver");
        }
        return content;
    }

    private PreparedDocument prepare(byte[] bytes, JobRequest r) throws Exception {
        FileKind kind = FileKind.detect(bytes);
        if (r.converter() != null) return toPostScript(bytes, kind, r);
        if (!kind.isPrintReady() && kind != FileKind.UNKNOWN && imageMode == AppConfig.ImageMode.SEND_ORIGINAL) {
            String name = r.file().getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
            return new PreparedDocument(bytes, DocumentNormalizer.safeName(r.jobName()) + ext, kind.mimeType(),
                    kind, null, List.of("Sent unconverted"));
        }
        return normalizer.prepare(bytes, r.jobName(), r.sizing().pageSpec(r.paper()), r.allowLowRes());
    }

    /** Delete or move a file after it was printed/sent successfully. Returns what happened. */
    public String afterSuccess(Path file) {
        try {
            if (afterSend == AppConfig.AfterSend.DELETE) {
                deleteWithRetry(file);
                return "deleted from hot folder";
            }
            return "moved to " + moveInto(file, HotFolderWatcher.SENT);
        } catch (IOException e) {
            return "sent, but could not remove it from the hot folder: " + e.getMessage();
        }
    }

    /** DRIVER_API method for imposed / front-back sheets: the sheet PDF goes through the driver. */
    private PreparedDocument sheetToPostScript(PreparedDocument pdf, JobRequest r) throws Exception {
        Path tmp = Files.createTempFile("fiery-sheet-", ".pdf");
        try {
            Files.write(tmp, pdf.content());
            String title = useOriginalFileName ? originalUploadName(r) : DocumentNormalizer.safeName(r.jobName());
            byte[] ps = PostScriptConverter.convert(tmp, FileKind.PDF, r.converter(), 0, 0, r.sizing(), title,
                    r.sheet() != null && r.sheet().duplex());
            return new PreparedDocument(ps, DocumentNormalizer.safeName(r.jobName()) + ".ps", "application/postscript",
                    FileKind.PDF, pdf.effectiveDpi(), pdf.warnings());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** DRIVER_API method: convert with the Fiery driver (like Ctrl+P, printed to a file). */
    private PreparedDocument toPostScript(byte[] bytes, FileKind kind, JobRequest r) throws Exception {
        String base = DocumentNormalizer.safeName(r.jobName());
        if (kind == FileKind.POSTSCRIPT) {
            return new PreparedDocument(bytes, base + ".ps", "application/postscript", kind, null, List.of());
        }
        if (kind == FileKind.UNKNOWN) throw new DocumentException("The file is empty or unreadable.");
        double w = r.paper() != null && r.paper().hasSize() ? r.paper().widthPt() : 0;
        double h = r.paper() != null && r.paper().hasSize() ? r.paper().heightPt() : 0;

        Integer dpi = null;
        List<String> warnings = new ArrayList<>();
        if (kind != FileKind.PDF) {
            Optional<Dimension> px = DocumentNormalizer.imageSize(r.file());
            if (px.isEmpty()) throw new DocumentException("Unsupported file type. Use JPG, PNG, TIFF, BMP, GIF, WebP, PSD or PDF.");
            dpi = r.sizing().pageSpec(r.paper()).layout(px.get().width, px.get().height).dpi();
            if (dpi < minDpi && !r.allowLowRes()) {
                throw new DocumentException("Image is only " + dpi + " DPI at this size (minimum " + minDpi + ").");
            }
        }
        String title = useOriginalFileName ? originalUploadName(r) : base;
        byte[] ps = PostScriptConverter.convert(r.file(), kind, r.converter(), w, h, r.sizing(), title, false);
        if (!PostScriptConverter.looksLikePostScript(ps)) {
            warnings.add("Driver output does not look like PostScript – is '" + r.converter() + "' a PostScript driver?");
        }
        return new PreparedDocument(ps, base + ".ps", "application/postscript", kind, dpi, List.copyOf(warnings));
    }

    public Path moveToSkipped(Path file) {
        try {
            Path moved = moveInto(file, HotFolderWatcher.SKIPPED);
            JobLog.skipped(file, "moved to " + moved);
            return moved;
        } catch (IOException e) {
            return file;
        }
    }

    public Path moveToFailed(Path file, Throwable error) {
        try {
            if (!Files.exists(file)) return file;
            Path moved = moveInto(file, HotFolderWatcher.FAILED);
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            Files.writeString(moved.resolveSibling(moved.getFileName() + ".error.txt"),
                    error.getMessage() + System.lineSeparator() + System.lineSeparator() + sw,
                    StandardCharsets.UTF_8);
            return moved;
        } catch (IOException e) {
            return file;
        }
    }

    // Windows (antivirus, Explorer preview) may briefly lock files, so retry.

    private static void deleteWithRetry(Path file) throws IOException {
        IOException last = null;
        for (int i = 0; i < 6; i++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (IOException e) {
                last = e;
                sleep();
            }
        }
        throw last;
    }

    private static Path moveInto(Path file, String sub) throws IOException {
        Path dir = file.getParent().resolve(sub);
        Files.createDirectories(dir);
        Path target = dir.resolve(LocalDateTime.now().format(STAMP) + "_" + file.getFileName());
        IOException last = null;
        for (int i = 0; i < 6; i++) {
            try {
                return Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                last = e;
                sleep();
            }
        }
        throw last;
    }

    private static void sleep() {
        try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static long sizeOf(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0; }
    }
}
