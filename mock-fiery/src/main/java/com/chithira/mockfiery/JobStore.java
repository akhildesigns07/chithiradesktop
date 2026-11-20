package com.chithira.mockfiery;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Received jobs: saved to <data>/jobs as the file plus a .json with what was sent. */
final class JobStore {

    static final class Job {
        String id;
        String title;
        String received;
        String source;          // "API" or "Port 9100 (driver)"
        String queue;
        String format;          // PDF / PostScript / TIFF
        int pages;
        String pageSize = "";   // e.g. "330 × 483 mm"
        String paperId = "";
        String paperName = "";
        String copies = "1";
        boolean duplex;
        long bytes;
        String state = "Held";
        String file;            // file name inside the jobs folder
        String uploadContentType = "";
        Map<String, Object> attributes = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("title", title);
            m.put("received", received);
            m.put("source", source);
            m.put("queue", queue);
            m.put("state", state);
            m.put("format", format);
            m.put("pages", pages);
            m.put("pageSize", pageSize);
            m.put("pcmid", paperId);
            m.put("paper", paperName);
            m.put("copies", copies);
            m.put("duplex", duplex);
            m.put("bytes", bytes);
            m.put("file", file);
            m.put("uploadContentType", uploadContentType);
            m.put("attributes", attributes);
            m.put("notes", notes);
            return m;
        }

        @SuppressWarnings("unchecked")
        static Job fromMap(Map<String, Object> m) {
            Job j = new Job();
            j.id = str(m.get("id"));
            j.title = str(m.get("title"));
            j.received = str(m.get("received"));
            j.source = str(m.get("source"));
            j.queue = str(m.get("queue"));
            j.state = str(m.get("state"));
            j.format = str(m.get("format"));
            j.pages = Integer.parseInt(str(m.get("pages")).isEmpty() ? "0" : str(m.get("pages")));
            j.pageSize = str(m.get("pageSize"));
            j.paperId = str(m.get("pcmid"));
            j.paperName = str(m.get("paper"));
            j.copies = str(m.get("copies"));
            j.duplex = Boolean.TRUE.equals(m.get("duplex"));
            j.bytes = Long.parseLong(str(m.get("bytes")).isEmpty() ? "0" : str(m.get("bytes")));
            j.file = str(m.get("file"));
            j.uploadContentType = str(m.get("uploadContentType"));
            if (m.get("attributes") instanceof Map<?, ?> a) j.attributes = (Map<String, Object>) a;
            if (m.get("notes") instanceof List<?> n) n.forEach(x -> j.notes.add(str(x)));
            return j;
        }

        private static String str(Object o) { return o == null ? "" : o.toString(); }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path dir;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1001);

    JobStore(Path dataDir) throws IOException {
        this.dir = dataDir.resolve("jobs");
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".job.json")).toList()) {
                try {
                    Job j = Job.fromMap(Json.object(Files.readString(p, StandardCharsets.UTF_8)));
                    jobs.put(j.id, j);
                    nextId.set(Math.max(nextId.get(), Integer.parseInt(j.id) + 1));
                } catch (Exception ignored) {
                    // skip broken record
                }
            }
        }
    }

    /** What kind of print data this is, or null if the Fiery couldn't process it. */
    static String detectFormat(byte[] b) {
        if (b.length < 4) return null;
        String head = new String(b, 0, Math.min(b.length, 4096), StandardCharsets.ISO_8859_1);
        if (head.startsWith("%PDF")) return "PDF";
        if (head.startsWith("%!") || head.contains("\n%!PS") || head.contains("@PJL")) return "PostScript";
        if ((b[0] == 'I' && b[1] == 'I' && b[2] == 42) || (b[0] == 'M' && b[1] == 'M' && b[3] == 42)) return "TIFF";
        return null;
    }

    synchronized Job add(String title, String source, String queue, String format, byte[] data,
                         String uploadContentType, Map<String, Object> attributes) throws IOException {
        Job j = new Job();
        j.id = String.valueOf(nextId.getAndIncrement());
        j.title = title == null || title.isBlank() ? "Untitled" : title;
        j.received = LocalDateTime.now().format(TIME);
        j.source = source;
        j.queue = queue;
        j.state = "print".equalsIgnoreCase(queue) ? "Printed" : "Held";
        j.format = format;
        j.bytes = data.length;
        j.uploadContentType = uploadContentType == null ? "" : uploadContentType;
        if (attributes != null) j.attributes = attributes;
        // keep the original name and extension (e.g. 1004_card_front.jpg), like the Fiery shows the job title;
        // the content itself is the PDF/PostScript that was sent (see the Format column)
        inspect(j, data);                          // may take the title from the driver's job header
        j.file = j.id + "_" + safeFileName(j.title);
        Files.write(dir.resolve(j.file), data);
        save(j);
        jobs.put(j.id, j);
        return j;
    }

    /** Windows does not allow \ / : * ? " < > | in file names. */
    static String safeFileName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (s.isEmpty()) s = "job";
        return s.length() > 150 ? s.substring(0, 150) : s;
    }

    void save(Job j) throws IOException {
        Files.writeString(dir.resolve(j.id + ".job.json"), Json.pretty(j.toMap()), StandardCharsets.UTF_8);
    }

    List<Job> all() {
        return jobs.values().stream().sorted(Comparator.comparing((Job j) -> Integer.parseInt(j.id)).reversed()).toList();
    }

    Job get(String id) { return jobs.get(id); }

    Path fileOf(Job j) { return dir.resolve(j.file); }

    synchronized void clear() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) Files.deleteIfExists(p);
        }
        jobs.clear();
    }

    // ------------------------------------------------------------------ inspection

    private static final Pattern PAGES = Pattern.compile("%%Pages:\\s*(\\d+)");
    private static final Pattern PAGE = Pattern.compile("(?m)^%%Page:");
    private static final Pattern TITLE = Pattern.compile("%%Title:\\s*\\(?([^)\\r\\n]*)\\)?");
    private static final Pattern PJL_NAME = Pattern.compile("@PJL JOB NAME\\s*=\\s*\"?([^\"\\r\\n]*)");
    private static final Pattern COPIES = Pattern.compile("/NumCopies\\s+(\\d+)");
    private static final Pattern PAGESIZE = Pattern.compile("/PageSize\\s*\\[\\s*([\\d.]+)\\s+([\\d.]+)\\s*]");
    private static final Pattern DUPLEX = Pattern.compile("/Duplex\\s+true");

    private static void inspect(Job j, byte[] data) {
        try {
            switch (j.format) {
                case "PDF" -> {
                    try (PDDocument d = Loader.loadPDF(data)) {
                        j.pages = d.getNumberOfPages();
                        if (j.pages > 0) j.pageSize = mm(d.getPage(0).getMediaBox());
                    }
                }
                case "TIFF" -> {
                    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                        Iterator<ImageReader> it = ImageIO.getImageReaders(in);
                        if (it.hasNext()) {
                            ImageReader r = it.next();
                            r.setInput(in);
                            j.pages = r.getNumImages(true);
                            r.dispose();
                        }
                    }
                }
                default -> {
                    String text = new String(data, 0, Math.min(data.length, 8_000_000), StandardCharsets.ISO_8859_1);
                    // count the page markers; "%%Pages:" may say "(atend)" or appear several times
                    j.pages = (int) PAGE.matcher(text).results().count();
                    if (j.pages == 0) {
                        Matcher m = PAGES.matcher(text);
                        if (m.find()) j.pages = Integer.parseInt(m.group(1));
                    }
                    Matcher ps = PAGESIZE.matcher(text);
                    if (ps.find()) {
                        j.pageSize = Math.round(Double.parseDouble(ps.group(1)) / 72 * 25.4) + " × "
                                + Math.round(Double.parseDouble(ps.group(2)) / 72 * 25.4) + " mm";
                    }
                    Matcher c = COPIES.matcher(text);
                    if (c.find()) j.notes.add("PostScript asks for " + c.group(1) + " copies");
                    if (DUPLEX.matcher(text).find()) { j.duplex = true; j.notes.add("PostScript asks for duplex"); }
                    if (j.source.startsWith("Port")) {
                        Matcher pj = PJL_NAME.matcher(text);
                        Matcher t = TITLE.matcher(text);
                        if (pj.find() && !pj.group(1).isBlank()) j.title = pj.group(1).trim();
                        else if (t.find() && !t.group(1).isBlank()) j.title = t.group(1).trim();
                        if (c.reset().find()) j.copies = c.group(1);
                    }
                }
            }
        } catch (Exception e) {
            j.notes.add("Could not read the file: " + e.getMessage());
        }
    }

    private static String mm(PDRectangle r) {
        return Math.round(r.getWidth() / 72 * 25.4) + " × " + Math.round(r.getHeight() / 72 * 25.4) + " mm";
    }
}
