package com.chithira.fierydesk.core;

import com.chithira.fierydesk.App;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuration from the EXTERNAL file  fiery-hotfolder.properties.
 *
 * Where the file is looked for, first match wins:
 *   1. -Dfiery.config=C:/path/to/file.properties   (JVM option)
 *   2. environment variable FIERY_HOTFOLDER_CONFIG
 *   3. fiery-hotfolder.properties next to "Fiery Hot Folder.exe" (Windows .exe build)
 *   4. fiery-hotfolder.properties next to the jar
 *   5. fiery-hotfolder.properties in the current working folder
 * If none exists, a commented template is created next to the jar.
 *
 * Values may use ${ENV_VAR} placeholders, e.g. fiery.password=${FIERY_PASSWORD}.
 *
 * Small UI memory (last paper, last sizing) is kept separately in
 * <user home>/.chithira-fiery/state.properties.
 */
public final class AppConfig {

    public static final String FILE_NAME = "fiery-hotfolder.properties";
    public static final Path DIR = Path.of(System.getProperty("user.home"), ".chithira-fiery");
    public static final Path STATE_FILE = DIR.resolve("state.properties");

    /** Every job goes to the Fiery Hold queue so staff check it before printing. */
    public static final String QUEUE = "hold";

    public enum SendMethod {
        API("Fiery API → Hold queue (paper catalog)"),
        DRIVER_API("Fiery driver → PostScript → API (Hold, job id)"),
        DRIVER("Printer driver – exactly like Ctrl+P");
        private final String label;
        SendMethod(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public enum ImageMode {
        CONVERT_TO_PDF("Convert to PDF"),
        SEND_ORIGINAL("Send original unchanged");
        private final String label;
        ImageMode(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public enum AfterSend {
        DELETE("Delete from hot folder"),
        MOVE_TO_SENT("Move to Sent folder");
        private final String label;
        AfterSend(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    /** Everything needed to talk to the Fiery API. */
    public record FieryApi(String baseUrl, String basePath, String loginPath, String logoutPath,
                           String jobsPath, String paperCatalogPath,
                           String apiKey, String username, String password, boolean trustSelfSigned,
                           Duration connectTimeout, Duration requestTimeout, Duration uploadTimeout) {

        public String url(String path) {
            String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return b + slash(basePath) + slash(path);
        }

        private static String slash(String p) {
            if (p == null || p.isBlank()) return "";
            String s = p.trim();
            if (!s.startsWith("/")) s = "/" + s;
            return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
        }
    }

    // --------------------------------------------------------------- values

    public Path configFile = Path.of(FILE_NAME);

    public FieryApi fiery = new FieryApi("https://192.168.1.50", "/live/api/v5", "/login", "/logout",
            "/jobs", "/papercatalog", "", "", "", true,
            Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(300));

    public Path hotFolder = defaultHotFolder();
    public boolean processExistingFiles = true;
    public AfterSend afterSend = AfterSend.DELETE;
    public ImageMode imageMode = ImageMode.CONVERT_TO_PDF;
    public int minDpi = 200;
    public int warnDpi = 300;

    public SendMethod sendMethod = SendMethod.API;
    public boolean useOriginalFileName = true;
    public String driverPrinterName = "";
    public String driverPaper = "";
    public boolean driverCombineIntoOneJob = false;
    public boolean driverShowPrintDialog = false;

    // imposition and front/back
    public String imposePresets = com.chithira.fierydesk.impose.Preset.DEFAULTS;
    public String imposeDefaultPaperId = "5598";
    public double imposeMarginMm = 5;
    public double imposeGapMm = 2;
    public double imposeBleedMm = 0;
    public boolean imposeAllowRotation = true;
    public boolean imposeFill = true;
    public boolean imposeInfoLine = true;
    public double imposeBackOffsetXmm = 0;
    public double imposeBackOffsetYmm = 0;
    public boolean autoPairFrontBack = true;
    public String duplexAttributeName = "duplex";
    public String duplexAttributeValue = "true";
    public boolean lastImpose = false;

    // UI memory (state.properties)
    public String lastPaperId = "";
    public Sizing lastSizing = Sizing.FIT;

    /** Problems found while reading the file (shown to the user). */
    public final List<String> problems = new ArrayList<>();

    public boolean isFieryConfigured() {
        return !fiery.baseUrl().isBlank() && !fiery.apiKey().isBlank()
                && !fiery.username().isBlank() && !fiery.password().isBlank();
    }

    // --------------------------------------------------------------- loading

    public static AppConfig load() {
        Path file = locate();
        if (!Files.exists(file)) {
            try {
                writeTemplate(file);
            } catch (IOException e) {
                // fall through: defaults are used, problem reported below
            }
        }
        Properties p = new Properties();
        AppConfig c = new AppConfig();
        c.configFile = file;
        try {
            p.load(new StringReader(protectWindowsPaths(Files.readString(file, StandardCharsets.UTF_8))));
        } catch (IOException e) {
            c.problems.add("Cannot read " + file + ": " + e.getMessage());
        }

        c.fiery = new FieryApi(
                get(p, "fiery.base-url", "https://192.168.1.50", c),
                get(p, "fiery.api.base-path", "/live/api/v5", c),
                get(p, "fiery.api.login", "/login", c),
                get(p, "fiery.api.logout", "/logout", c),
                get(p, "fiery.api.jobs", "/jobs", c),
                get(p, "fiery.api.papercatalog", "/papercatalog", c),
                get(p, "fiery.api-key", "", c),
                get(p, "fiery.username", "", c),
                get(p, "fiery.password", "", c),
                bool(p, "fiery.trust-self-signed-certificate", true, c),
                Duration.ofSeconds(integer(p, "fiery.timeout.connect-seconds", 15, c)),
                Duration.ofSeconds(integer(p, "fiery.timeout.request-seconds", 60, c)),
                Duration.ofSeconds(integer(p, "fiery.timeout.upload-seconds", 300, c)));

        String folder = get(p, "hotfolder.path", "", c);
        c.hotFolder = folder.isBlank() ? defaultHotFolder() : Path.of(folder);
        c.processExistingFiles = bool(p, "hotfolder.process-existing-files", true, c);
        c.afterSend = enumValue(p, "hotfolder.after-send", AfterSend.class, AfterSend.DELETE, c);
        c.imageMode = enumValue(p, "image.mode", ImageMode.class, ImageMode.CONVERT_TO_PDF, c);
        c.minDpi = integer(p, "quality.min-dpi", 200, c);
        c.warnDpi = Math.max(c.minDpi, integer(p, "quality.warn-dpi", 300, c));
        c.sendMethod = enumValue(p, "send.method", SendMethod.class, SendMethod.API, c);
        c.useOriginalFileName = bool(p, "api.use-original-filename", true, c);
        c.driverPrinterName = get(p, "driver.printer-name", "", c);
        c.driverPaper = get(p, "driver.paper", "", c);
        c.driverCombineIntoOneJob = bool(p, "driver.combine-into-one-job", false, c);
        c.driverShowPrintDialog = bool(p, "driver.show-print-dialog", false, c);
        c.imposePresets = get(p, "impose.presets", c.imposePresets, c);
        c.imposeDefaultPaperId = get(p, "impose.default-paper-id", c.imposeDefaultPaperId, c);
        c.imposeMarginMm = decimal(p, "impose.margin-mm", 5, c);
        c.imposeGapMm = decimal(p, "impose.gap-mm", 2, c);
        c.imposeBleedMm = decimal(p, "impose.bleed-mm", 0, c);
        c.imposeAllowRotation = bool(p, "impose.allow-rotation", true, c);
        c.imposeFill = bool(p, "impose.fill-piece", true, c);
        c.imposeInfoLine = bool(p, "impose.info-line", true, c);
        c.imposeBackOffsetXmm = decimal(p, "impose.back-offset-x-mm", 0, c);
        c.imposeBackOffsetYmm = decimal(p, "impose.back-offset-y-mm", 0, c);
        c.autoPairFrontBack = bool(p, "frontback.auto-pair", true, c);
        c.duplexAttributeName = get(p, "api.duplex-attribute-name", c.duplexAttributeName, c);
        c.duplexAttributeValue = get(p, "api.duplex-attribute-value", c.duplexAttributeValue, c);

        if (!c.fiery.baseUrl().startsWith("https://") && !c.fiery.baseUrl().startsWith("http://")) {
            c.problems.add("fiery.base-url must start with https:// (now: " + c.fiery.baseUrl() + ")");
        }
        c.loadState();
        return c;
    }

    /** Finds the external config file (see class comment). */
    public static Path locate() {
        String sys = System.getProperty("fiery.config");
        if (sys != null && !sys.isBlank()) return Path.of(sys);
        String env = System.getenv("FIERY_HOTFOLDER_CONFIG");
        if (env != null && !env.isBlank()) return Path.of(env);
        Path exeDir = exeDirectory();
        if (exeDir != null && Files.exists(exeDir.resolve(FILE_NAME))) return exeDir.resolve(FILE_NAME);
        Path appDir = appDirectory();
        if (appDir != null) {
            Path besideJar = appDir.resolve(FILE_NAME);
            if (Files.exists(besideJar)) return besideJar;
        }
        Path cwd = Path.of("").toAbsolutePath().resolve(FILE_NAME);
        if (Files.exists(cwd)) return cwd;
        if (exeDir != null) return exeDir.resolve(FILE_NAME);      // where the template will be created
        return appDir != null ? appDir.resolve(FILE_NAME) : cwd;
    }

    /** Folder of "Fiery Hot Folder.exe" when started from the Windows .exe (set by the jpackage launcher). */
    static Path exeDirectory() {
        String exe = System.getProperty("jpackage.app-path");
        if (exe == null || exe.isBlank()) return null;
        Path parent = Path.of(exe).toAbsolutePath().getParent();
        return parent;
    }

    /** Folder containing the running jar (or the classes folder when run from an IDE). */
    static Path appDirectory() {
        try {
            Path p = Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(p) ? p.getParent() : p;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    // --------------------------------------------------------------- state

    private void loadState() {
        if (!Files.exists(STATE_FILE)) return;
        Properties s = new Properties();
        try (InputStream in = Files.newInputStream(STATE_FILE)) {
            s.load(in);
            lastPaperId = s.getProperty("lastPaperId", "");
            lastSizing = Sizing.valueOf(s.getProperty("lastSizing", Sizing.FIT.name()));
            if (s.containsKey("lastSendMethod")) sendMethod = SendMethod.valueOf(s.getProperty("lastSendMethod"));
            if (!s.getProperty("lastDriverPrinter", "").isBlank()) driverPrinterName = s.getProperty("lastDriverPrinter");
            if (!s.getProperty("lastDriverPaper", "").isBlank()) driverPaper = s.getProperty("lastDriverPaper");
            lastImpose = Boolean.parseBoolean(s.getProperty("lastImpose", "false"));
        } catch (Exception ignored) {
            // state is only a convenience
        }
    }

    public synchronized void saveState() throws IOException {
        Files.createDirectories(DIR);
        Properties s = new Properties();
        s.setProperty("lastPaperId", lastPaperId == null ? "" : lastPaperId);
        s.setProperty("lastSizing", lastSizing.name());
        s.setProperty("lastSendMethod", sendMethod.name());
        s.setProperty("lastDriverPrinter", driverPrinterName == null ? "" : driverPrinterName);
        s.setProperty("lastDriverPaper", driverPaper == null ? "" : driverPaper);
        s.setProperty("lastImpose", String.valueOf(lastImpose));
        try (OutputStream out = Files.newOutputStream(STATE_FILE)) {
            s.store(out, "UI memory for Chithira Fiery Hot Folder (safe to delete)");
        }
    }

    // --------------------------------------------------------------- template

    public static void writeTemplate(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        String hot = defaultHotFolder().toString().replace('\\', '/');
        String t = """
                # =====================================================================
                #  Chithira Printers - Fiery Hot Folder configuration
                #  Edit this file, save it, then click "Reload config" in the app.
                #  Windows paths: use forward slashes, e.g. C:/FieryHotFolder
                #  Secrets can come from environment variables: fiery.password=${FIERY_PASSWORD}
                # =====================================================================

                # ---------------------------------------------------------------- Hot folder
                # Folder to watch. Files copied here are offered for sending to the Fiery.
                hotfolder.path=%s
                # Also offer files that are already in the folder when the app starts
                hotfolder.process-existing-files=true
                # After the Fiery accepts a job: DELETE or MOVE_TO_SENT
                hotfolder.after-send=DELETE

                # ---------------------------------------------------------------- Fiery API
                fiery.base-url=https://192.168.1.50
                fiery.api-key=
                fiery.username=
                fiery.password=
                # Fiery servers use a self-signed certificate. true = accept it (shop network only)
                fiery.trust-self-signed-certificate=true

                # API paths (change only if your Fiery API version differs)
                fiery.api.base-path=/live/api/v5
                fiery.api.login=/login
                fiery.api.logout=/logout
                fiery.api.jobs=/jobs
                fiery.api.papercatalog=/papercatalog

                # Timeouts in seconds
                fiery.timeout.connect-seconds=15
                fiery.timeout.request-seconds=60
                fiery.timeout.upload-seconds=300

                # ---------------------------------------------------------------- How to send
                # Default method in the "Files waiting" window (staff can switch per batch):
                #   API        = upload with the Fiery API to the Hold queue, paper from the Paper Catalog
                #   DRIVER_API = convert to PostScript with the Fiery driver (like Ctrl+P, printed to a file),
                #                then upload that PostScript with the API (Hold queue, Paper Catalog, job id)
                #   DRIVER     = print through the installed Fiery printer driver, exactly like Ctrl+P
                send.method=API
                # Upload with the original file name and extension (e.g. Wedding_Invite.jpg),
                # even when the content was converted to PDF or PostScript
                api.use-original-filename=true

                # ---------------------------------------------------------------- Printer driver (DRIVER method)
                # Windows printer name exactly as in Settings > Printers, e.g. the Fiery Hold queue printer.
                # Also used by DRIVER_API to convert files to PostScript (blank = Java's built-in PostScript)
                driver.printer-name=
                # Paper size name from the driver's list (e.g. iso-a4, or a driver size like 13x19). Blank = driver default
                driver.paper=
                # true = all ticked images become pages of ONE job; false = one job per file
                driver.combine-into-one-job=false
                # true = show the normal Windows print dialog once per batch (access to the Fiery driver Properties)
                driver.show-print-dialog=false

                # ---------------------------------------------------------------- Imposition (step and repeat)
                # Piece sizes offered in the window: Name:WIDTHxHEIGHTmm (or in / cm), separated by ;
                impose.presets=%s
                # Paper Catalog id chosen automatically when Impose is ticked (blank = keep current paper)
                impose.default-paper-id=5598
                # Defaults in the window (staff can change them per job)
                impose.margin-mm=5
                impose.gap-mm=2
                impose.bleed-mm=0
                # Turn pieces 90 degrees when more fit that way
                impose.allow-rotation=true
                # true = artwork covers each piece (crops if shapes differ); false = fits inside it
                impose.fill-piece=true
                # Small line in the margin: file name, N-up, sheets, date
                impose.info-line=true
                # Crop marks are always printed on the front sheet and never on the back.
                # Nudge the back side if the press does not line up front and back exactly (mm)
                impose.back-offset-x-mm=0
                impose.back-offset-y-mm=0

                # ---------------------------------------------------------------- Front and back
                # Pair files automatically when names show it: card_front/card_back, card_F/card_B, side1/side2
                frontback.auto-pair=true
                # Job attribute the API sends for two-sided jobs. Find your Fiery's exact name/value by
                # setting duplex on a held job in Command WorkStation and reading GET /jobs/{id}
                api.duplex-attribute-name=duplex
                api.duplex-attribute-value=true

                # ---------------------------------------------------------------- Files
                # Image files (PNG, JPG...): CONVERT_TO_PDF (recommended) or SEND_ORIGINAL
                # PDF, PostScript and TIFF are always sent unchanged. Queue is always "hold".
                image.mode=CONVERT_TO_PDF
                # Block images below this resolution on the chosen paper (unless "Allow low resolution")
                quality.min-dpi=200
                # Show a warning below this resolution
                quality.warn-dpi=300
                """.formatted(hot, com.chithira.fierydesk.impose.Preset.DEFAULTS);
        Files.writeString(file, t, StandardCharsets.UTF_8);
    }

    // --------------------------------------------------------------- helpers

    private static Path defaultHotFolder() {
        Path desktop = Path.of(System.getProperty("user.home"), "Desktop");
        return (Files.isDirectory(desktop) ? desktop : Path.of(System.getProperty("user.home")))
                .resolve("Fiery HotFolder");
    }

    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private static String get(Properties p, String key, String def, AppConfig c) {
        String v = p.getProperty(key);
        if (v == null) return def;
        v = v.trim();
        Matcher m = ENV.matcher(v);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String env = System.getenv(m.group(1));
            if (env == null) {
                c.problems.add(key + ": environment variable " + m.group(1) + " is not set");
                env = "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(env));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean bool(Properties p, String key, boolean def, AppConfig c) {
        String v = get(p, key, String.valueOf(def), c).toLowerCase();
        if (v.equals("true") || v.equals("yes")) return true;
        if (v.equals("false") || v.equals("no")) return false;
        c.problems.add(key + " must be true or false (now: " + v + ")");
        return def;
    }

    private static double decimal(Properties p, String key, double def, AppConfig c) {
        String v = get(p, key, String.valueOf(def), c);
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            c.problems.add(key + " must be a number (now: " + v + ")");
            return def;
        }
    }

    private static int integer(Properties p, String key, int def, AppConfig c) {
        String v = get(p, key, String.valueOf(def), c);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            c.problems.add(key + " must be a number (now: " + v + ")");
            return def;
        }
    }

    private static <E extends Enum<E>> E enumValue(Properties p, String key, Class<E> type, E def, AppConfig c) {
        String v = get(p, key, def.name(), c).toUpperCase();
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            StringBuilder allowed = new StringBuilder();
            for (E x : type.getEnumConstants()) allowed.append(allowed.length() == 0 ? "" : ", ").append(x.name());
            c.problems.add(key + " must be one of " + allowed + " (now: " + v + ")");
            return def;
        }
    }

    /**
     * In .properties files a backslash is an escape character, so C:\FieryHotFolder
     * would be read as "C:FieryHotFolder". Keep backslashes literal in *.path lines.
     */
    static String protectWindowsPaths(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            String t = line.stripLeading();
            int eq = t.indexOf('=');
            if (eq > 0 && !t.startsWith("#") && t.substring(0, eq).trim().endsWith(".path")) {
                line = line.replace("\\", "\\\\");
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
