package com.chithira.fierydesk.ui;

import com.chithira.fierydesk.core.AppConfig;
import com.chithira.fierydesk.core.HotFolderWatcher;
import com.chithira.fierydesk.core.JobLog;
import com.chithira.fierydesk.core.JobProcessor;
import com.chithira.fierydesk.core.JobRequest;
import com.chithira.fierydesk.core.PaperCatalog;
import com.chithira.fierydesk.core.SheetJob;
import com.chithira.fierydesk.core.SheetMaker;
import com.chithira.fierydesk.document.DocumentException;
import com.chithira.fierydesk.driver.DriverBatch;
import com.chithira.fierydesk.driver.DriverPrintOptions;
import com.chithira.fierydesk.driver.DriverPrinter;
import com.chithira.fierydesk.fiery.FieryClient;
import com.chithira.fierydesk.fiery.FieryException;

import javax.net.ssl.SSLException;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.print.PrinterJob;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Main window: hot folder, paper catalog status, job log. */
public final class MainWindow {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int COL_STATUS = 2, COL_JOB = 3, COL_DETAILS = 4;

    private final JFrame frame = new JFrame("Chithira Printers – Fiery Hot Folder");
    private final JTextField folderField = new JTextField(40);
    private final JLabel configLabel = new JLabel();
    private final JButton startStop = new JButton("Start watching");
    private final JButton pendingButton = new JButton("Waiting files (0)");
    private final JLabel paperStatus = new JLabel("Paper catalog: not loaded");
    private final JLabel statusLabel = new JLabel("Stopped");
    private final DefaultTableModel log = new DefaultTableModel(
            new Object[]{"Time", "File", "Status", "Fiery job", "Details"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };

    private final Map<Path, Integer> rowOf = new HashMap<>();
    private final ExecutorService sender = daemonExecutor("fiery-sender");
    private final ExecutorService background = daemonExecutor("fiery-background");
    private final PaperCatalog catalog = new PaperCatalog();

    private AppConfig cfg;
    private FieryClient fiery;
    private JobProcessor processor;
    private HotFolderWatcher watcher;
    private PendingJobsWindow pending;
    private TrayIcon tray;

    public MainWindow(AppConfig cfg) {
        applyConfig(cfg);
        buildUi();
        pending = new PendingJobsWindow(cfg, appIcon(), this::sendAll, this::printViaDriver, this::skipAll, this::refreshPapers);
    }

    public void show() {
        frame.setVisible(true);

        // Paper catalog: show the saved copy immediately, then fetch a fresh one from the Fiery
        if (catalog.loadCache()) papersLoaded();

        reportConfigProblems();
        if (!cfg.isFieryConfigured()) {
            JOptionPane.showMessageDialog(frame,
                    "Please fill in the Fiery address, API key, user name and password in:\n\n"
                            + cfg.configFile + "\n\nThen save the file and click 'Reload config'.",
                    "First-time setup", JOptionPane.INFORMATION_MESSAGE);
            editConfig();
            return;
        }
        refreshPapers();
        startWatching();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        folderField.setText(cfg.hotFolder.toString());
        folderField.setEditable(false);
        JButton openFolder = new JButton("Open folder");
        JButton editConfig = new JButton("Edit config");
        JButton reloadConfig = new JButton("Reload config");
        JButton test = new JButton("Test connection");
        JButton refresh = new JButton("Refresh papers");
        JButton openLog = new JButton("Open log file");

        openFolder.addActionListener(e -> openPath(cfg.hotFolder, true));
        startStop.addActionListener(e -> { if (watcher == null) startWatching(); else stopWatching(); });
        pendingButton.addActionListener(e -> { if (pending.pendingCount() > 0) pending.popUp(); });
        editConfig.addActionListener(e -> editConfig());
        reloadConfig.addActionListener(e -> reloadConfig());
        test.addActionListener(e -> testConnection());
        refresh.addActionListener(e -> refreshPapers());
        openLog.addActionListener(e -> openPath(JobLog.currentFile(), false));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row1.add(new JLabel("Hot folder:"));
        row1.add(folderField);
        row1.add(openFolder);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row2.add(startStop);
        row2.add(pendingButton);
        row2.add(test);
        row2.add(refresh);
        row2.add(openLog);
        row2.add(editConfig);
        row2.add(reloadConfig);

        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row3.add(paperStatus);

        JPanel row4 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row4.add(configLabel);
        updateConfigLabel();

        JPanel north = new JPanel(new GridLayout(4, 1));
        north.add(row1);
        north.add(row2);
        north.add(row3);
        north.add(row4);

        JTable table = new JTable(log);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(70);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.getColumnModel().getColumn(3).setPreferredWidth(80);
        table.getColumnModel().getColumn(4).setPreferredWidth(460);
        table.getColumnModel().getColumn(COL_STATUS).setCellRenderer(new StatusRenderer());

        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        south.add(statusLabel, BorderLayout.WEST);

        frame.setLayout(new BorderLayout());
        frame.add(north, BorderLayout.NORTH);
        frame.add(new JScrollPane(table), BorderLayout.CENTER);
        frame.add(south, BorderLayout.SOUTH);
        frame.setSize(1080, 560);
        frame.setLocationRelativeTo(null);
        frame.setIconImage(appIcon());

        setupTray();
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (tray != null) {
                    frame.setVisible(false);
                    tray.displayMessage("Still running", "Watching the hot folder. Right-click the tray icon to exit.",
                            TrayIcon.MessageType.INFO);
                } else if (confirmExit()) {
                    exit();
                }
            }
        });
    }

    private void setupTray() {
        if (!SystemTray.isSupported()) return;
        PopupMenu menu = new PopupMenu();
        MenuItem openItem = new MenuItem("Open");
        MenuItem exitItem = new MenuItem("Exit");
        openItem.addActionListener(e -> SwingUtilities.invokeLater(this::bringToFront));
        exitItem.addActionListener(e -> SwingUtilities.invokeLater(() -> { if (confirmExit()) exit(); }));
        menu.add(openItem);
        menu.addSeparator();
        menu.add(exitItem);
        tray = new TrayIcon(appIcon(), "Fiery Hot Folder", menu);
        tray.setImageAutoSize(true);
        tray.addActionListener(e -> SwingUtilities.invokeLater(this::bringToFront));
        try {
            SystemTray.getSystemTray().add(tray);
        } catch (AWTException e) {
            tray = null;
        }
    }

    // ------------------------------------------------------------ paper catalog

    private void refreshPapers() {
        paperStatus.setText("Paper catalog: loading from the Fiery…");
        FieryClient client = fiery;
        background.submit(() -> {
            try {
                catalog.refresh(client);
                SwingUtilities.invokeLater(this::papersLoaded);
            } catch (Exception e) {
                String msg = friendly(e);
                SwingUtilities.invokeLater(() -> paperStatus.setText(catalog.papers().isEmpty()
                        ? "⚠ Paper catalog not loaded: " + msg
                        : "⚠ Using saved paper list (" + catalog.papers().size() + " papers) – Fiery not reachable: " + msg));
            }
        });
    }

    private void papersLoaded() {
        String when = catalog.updated() == null ? "" :
                catalog.updated().atZone(ZoneId.systemDefault()).toLocalDateTime().format(DateTimeFormatter.ofPattern("dd MMM HH:mm"));
        paperStatus.setText("Paper catalog: " + catalog.papers().size() + " papers"
                + (catalog.isFromCache() ? " (saved copy from " + when + ")" : " (loaded from Fiery at " + when + ")"));
        if (pending != null) pending.setPapers(catalog.papers());
    }

    // ------------------------------------------------------------ watching

    private void startWatching() {
        Path folder = cfg.hotFolder;
        try {
            watcher = new HotFolderWatcher(folder,
                    p -> SwingUtilities.invokeLater(() -> onFileReady(p)),
                    msg -> SwingUtilities.invokeLater(() -> statusLabel.setText("⚠ " + msg)));
            watcher.start(cfg.processExistingFiles);
            startStop.setText("Stop watching");
            statusLabel.setText("Watching " + folder + " – copy files here to send them to the Fiery");
        } catch (IOException e) {
            watcher = null;
            error("Cannot watch folder", e.getMessage());
        }
    }

    private void stopWatching() {
        if (watcher != null) watcher.close();
        watcher = null;
        startStop.setText("Start watching");
        statusLabel.setText("Stopped");
    }

    private void onFileReady(Path file) {
        int row = log.getRowCount();
        log.addRow(new Object[]{LocalTime.now().format(TIME), file.getFileName().toString(), "Waiting", "", ""});
        rowOf.put(file, row);
        pending.addFile(file);
        updatePendingButton();
        if (tray != null && !frame.isVisible()) {
            tray.displayMessage("New file", file.getFileName().toString(), TrayIcon.MessageType.INFO);
        }
    }

    // ------------------------------------------------------------ send / skip

    private void sendAll(List<JobRequest> requests) {
        saveQuietly(); // remembers last paper, sizing and impose choice
        updatePendingButton();
        JobProcessor p = processor;     // settings may change while this runs
        for (JobRequest r : requests) {
            String what = r.sheet() == null ? r.copies() + " cop" + (r.copies() == 1 ? "y" : "ies")
                    : (r.sheet().impose() != null ? "imposing " + r.sheet().impose().quantity() + " pieces" : "front + back");
            update(r.file(), r.back(), "Queued", "", r.paper().name() + ", " + what);
            sender.submit(() -> {
                SwingUtilities.invokeLater(() -> update(r.file(), r.back(), "Sending…", "", r.paper().name()));
                try {
                    JobProcessor.Result res = p.process(r);
                    String details = res.submittedAs()
                            + (res.dpi() != null ? " (" + res.dpi() + " DPI)" : "")
                            + " – " + res.fileAction()
                            + (res.warnings().isEmpty() ? "" : " – " + String.join(" ", res.warnings()));
                    SwingUtilities.invokeLater(() -> {
                        update(r.file(), r.back(), "Sent", res.jobId() == null ? "?" : res.jobId(), details);
                        forget(r.file());
                        if (r.back() != null) forget(r.back());
                        if (tray != null && !frame.isVisible()) {
                            tray.displayMessage("Sent to Fiery (Hold)", r.jobName() + " → job " + res.jobId(), TrayIcon.MessageType.INFO);
                        }
                    });
                } catch (Exception e) {
                    String msg = friendly(e);
                    SwingUtilities.invokeLater(() -> {
                        update(r.file(), r.back(), "Failed", "", msg + "  (file moved to Failed folder)");
                        forget(r.file());
                        if (r.back() != null) forget(r.back());
                        error("Could not send " + r.file().getFileName(), msg);
                    });
                }
            });
        }
    }

    /**
     * Prints a batch through the installed printer driver, the same path as Ctrl+P.
     * Imposed and front/back jobs are made into a sheet PDF first (two-sided when there is a back).
     * After the job is handed to the Windows spooler, the files are deleted (or moved) and logged.
     */
    private void printViaDriver(DriverBatch batch) {
        saveQuietly();
        updatePendingButton();
        DriverPrintOptions o = batch.options();
        String paperLabel = DriverPrinter.label(o.paper());
        for (Path f : batch.files()) {
            SheetJob sj = batch.sheets().get(f);
            update(f, sj == null ? null : sj.back(), "Queued", "", "Printer " + o.printerName() + ", " + paperLabel);
        }

        PrinterJob job;
        try {
            // With the print dialog this shows the normal Windows dialog (Swing thread), like Ctrl+P
            Optional<PrinterJob> prepared = DriverPrinter.prepare(o);
            if (prepared.isEmpty()) {                       // dialog cancelled: put the files back
                putBack(batch, "Print dialog cancelled");
                return;
            }
            job = prepared.get();
        } catch (Exception e) {
            putBack(batch, friendly(e));
            error("Cannot print", friendly(e));
            return;
        }

        JobProcessor p = processor;
        AppConfig c = cfg;
        List<Path> plain = batch.files().stream().filter(f -> !batch.sheets().containsKey(f)).toList();
        sender.submit(() -> {
            // imposed / front-back sheets
            for (Map.Entry<Path, SheetJob> e : batch.sheets().entrySet()) {
                Path front = e.getKey();
                SheetJob sj = e.getValue();
                SwingUtilities.invokeLater(() -> update(front, sj.back(), "Printing…", "", "Making the sheet and sending to " + o.printerName()));
                long size = sizeOf(front) + (sj.back() == null ? 0 : sizeOf(sj.back()));
                boolean ok;
                String message, action;
                Path tmp = null;
                try {
                    SheetMaker.Sheets sheets = SheetMaker.make(sj, c.minDpi, c.warnDpi);
                    tmp = Files.createTempFile("fiery-sheet-", ".pdf");
                    Files.write(tmp, sheets.pdf());
                    String warning = DriverPrinter.printSheetPdf(job, tmp, front.getFileName().toString(), o, sheets.copies(), sj.duplex());
                    ok = true;
                    message = "Printed " + sheets.summary() + warning;
                    action = p.afterSuccess(front) + (sj.back() == null ? "" : "; back: " + p.afterSuccess(sj.back()));
                } catch (Exception ex) {
                    ok = false;
                    message = friendly(ex);
                    Path moved = p.moveToFailed(front, ex);
                    if (sj.back() != null) p.moveToFailed(sj.back(), ex);
                    action = moved.equals(front) ? "left in hot folder" : "moved to " + moved;
                } finally {
                    if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                }
                JobLog.driverResult(ok, front, size, o.printerName(), sj.paperName(),
                        sj.impose() != null ? "IMPOSED" : sj.sizing().name(), 0, false, message, action);
                boolean done = ok;
                String details = message + " – " + action;
                SwingUtilities.invokeLater(() -> {
                    update(front, sj.back(), done ? "Printed" : "Failed", "driver", details);
                    forget(front);
                    if (sj.back() != null) forget(sj.back());
                    if (!done) error("Could not print " + front.getFileName(), details);
                });
            }
            // plain files, exactly like before
            if (!plain.isEmpty()) {
                DriverPrinter.printBatch(job, plain, o, new DriverPrinter.Listener() {
                    @Override public void started(Path file) {
                        SwingUtilities.invokeLater(() -> update(file, null, "Printing…", "", "Sending to " + o.printerName()));
                    }
                    @Override public void finished(DriverPrinter.Outcome out) {
                        Path file = out.file();
                        long size = sizeOf(file);
                        String action;
                        if (out.ok()) {
                            action = p.afterSuccess(file);
                        } else {
                            Path moved = p.moveToFailed(file, new Exception(out.message()));
                            action = moved.equals(file) ? "left in hot folder" : "moved to " + moved;
                        }
                        JobLog.driverResult(out.ok(), file, size, o.printerName(), paperLabel, o.sizing().name(),
                                o.copies(), o.combineIntoOneJob(), out.message(), action);
                        String details = out.message() + " on " + o.printerName() + ", " + paperLabel
                                + ", " + o.copies() + " cop" + (o.copies() == 1 ? "y" : "ies") + " – " + action;
                        SwingUtilities.invokeLater(() -> {
                            update(file, null, out.ok() ? "Printed" : "Failed", "driver", details);
                            forget(file);
                            if (!out.ok()) error("Could not print " + file.getFileName(), out.message());
                        });
                    }
                });
            }
        });
    }

    /** Dialog cancelled or printer missing: show the files in the waiting window again. */
    private void putBack(DriverBatch batch, String why) {
        for (Path f : batch.files()) {
            SheetJob sj = batch.sheets().get(f);
            update(f, sj == null ? null : sj.back(), "Waiting", "", why);
            pending.addFile(f);
            if (sj != null && sj.back() != null) pending.addFile(sj.back());
        }
        updatePendingButton();
    }

    private static long sizeOf(Path f) {
        try { return Files.size(f); } catch (IOException e) { return 0; }
    }

    private void skipAll(List<Path> files) {
        updatePendingButton();
        for (Path f : files) {
            processor.moveToSkipped(f);
            update(f, "Skipped", "", "Moved to Skipped folder");
            forget(f);
        }
    }

    // ------------------------------------------------------------ actions

    /** Opens the external properties file in a text editor. */
    private void editConfig() {
        try {
            if (!Files.exists(cfg.configFile)) AppConfig.writeTemplate(cfg.configFile);
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                new ProcessBuilder("notepad.exe", cfg.configFile.toString()).start();
            } else if (Desktop.getDesktop().isSupported(Desktop.Action.EDIT)) {
                Desktop.getDesktop().edit(cfg.configFile.toFile());
            } else {
                Desktop.getDesktop().open(cfg.configFile.toFile());
            }
            statusLabel.setText("Editing " + cfg.configFile + " – save it, then click 'Reload config'");
        } catch (Exception e) {
            error("Cannot open the config file", cfg.configFile + "\n" + e.getMessage());
        }
    }

    /** Re-reads the external properties file and restarts watching with the new values. */
    private void reloadConfig() {
        AppConfig fresh = AppConfig.load();
        boolean wasTrusting = cfg.fiery.trustSelfSigned();
        boolean wasWatching = watcher != null;
        boolean fieryChanged = !fresh.fiery.equals(cfg.fiery);
        fresh.lastPaperId = cfg.lastPaperId;
        fresh.lastSizing = cfg.lastSizing;

        stopWatching();
        applyConfig(fresh);
        pending.setConfig(fresh);
        folderField.setText(fresh.hotFolder.toString());
        updateConfigLabel();
        reportConfigProblems();

        if (!fresh.isFieryConfigured()) {
            statusLabel.setText("⚠ Fiery address, API key, user name or password missing in " + fresh.configFile);
            return;
        }
        if (fieryChanged || catalog.papers().isEmpty()) refreshPapers();
        if (wasWatching || fresh.isFieryConfigured()) startWatching();
        if (wasTrusting != fresh.fiery.trustSelfSigned()) {
            JOptionPane.showMessageDialog(frame, "Restart the app for the certificate setting to take full effect.");
        }
        statusLabel.setText("Config reloaded from " + fresh.configFile);
    }

    private void reportConfigProblems() {
        if (cfg.problems.isEmpty()) return;
        error("Problems in " + cfg.configFile.getFileName(),
                String.join("\n", cfg.problems) + "\n\nDefaults are used for these values.");
    }

    private void updateConfigLabel() {
        configLabel.setText("<html>Config: <b>" + cfg.configFile + "</b> &nbsp;·&nbsp; Fiery: " + cfg.fiery.baseUrl()
                + " &nbsp;·&nbsp; default send: " + cfg.sendMethod.name()
                + (cfg.driverPrinterName.isBlank() ? "" : " (printer " + cfg.driverPrinterName + ")")
                + " &nbsp;·&nbsp; image files: " + cfg.imageMode + " &nbsp;·&nbsp; after send: " + cfg.afterSend
                + " &nbsp;·&nbsp; queue: hold</html>");
    }

    private void testConnection() {
        statusLabel.setText("Testing connection to " + cfg.fiery.baseUrl() + " …");
        FieryClient client = fiery;
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                client.login();
                return "Connected and logged in to the Fiery.";
            }
            @Override protected void done() {
                try {
                    String ok = get();
                    statusLabel.setText(ok);
                    JOptionPane.showMessageDialog(frame, ok, "Connection OK", JOptionPane.INFORMATION_MESSAGE);
                    if (catalog.papers().isEmpty() || catalog.isFromCache()) refreshPapers();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    statusLabel.setText("Connection failed");
                    error("Connection failed", friendly(cause));
                }
            }
        }.execute();
    }

    private void openPath(Path p, boolean isFolder) {
        try {
            if (isFolder) Files.createDirectories(p);
            else if (!Files.exists(p)) {
                JOptionPane.showMessageDialog(frame, "No jobs logged this month yet.\nLog folder: " + JobLog.DIR);
                return;
            }
            Desktop.getDesktop().open(p.toFile());
        } catch (Exception e) {
            error("Cannot open", e.getMessage());
        }
    }

    private void exit() {
        stopWatching();
        sender.shutdown();
        try { fiery.logout(); } catch (Exception ignored) {}
        if (tray != null) SystemTray.getSystemTray().remove(tray);
        System.exit(0);
    }

    private boolean confirmExit() {
        String extra = pending.pendingCount() > 0 ? "\n" + pending.pendingCount() + " file(s) are still waiting." : "";
        return JOptionPane.showConfirmDialog(frame, "Stop watching the hot folder and exit?" + extra, "Exit",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    private void bringToFront() {
        frame.setVisible(true);
        frame.setExtendedState(Frame.NORMAL);
        frame.toFront();
    }

    // ------------------------------------------------------------ helpers

    private void applyConfig(AppConfig c) {
        this.cfg = c;
        if (fiery != null) {
            FieryClient old = fiery;
            background.submit(old::logout);
        }
        this.fiery = new FieryClient(c.fiery);
        this.processor = new JobProcessor(fiery, c);
    }

    private void saveQuietly() {
        try { cfg.saveState(); } catch (IOException e) { statusLabel.setText("⚠ Could not save last choices: " + e.getMessage()); }
    }

    private void updatePendingButton() {
        int n = pending == null ? 0 : pending.pendingCount();
        pendingButton.setText("Waiting files (" + n + ")");
    }

    /** Updates the log row of a file and, for front/back jobs, of its back too. */
    private void update(Path file, Path back, String status, String jobId, String details) {
        update(file, status, jobId, details);
        if (back != null) update(back, status, jobId, back.equals(file) ? details : "Back of " + file.getFileName());
    }

    private void update(Path file, String status, String jobId, String details) {
        Integer row = rowOf.get(file);
        if (row == null) return;
        log.setValueAt(status, row, COL_STATUS);
        log.setValueAt(jobId, row, COL_JOB);
        log.setValueAt(details, row, COL_DETAILS);
    }

    private void forget(Path file) {
        rowOf.remove(file);
        if (watcher != null) watcher.forget(file);
    }

    private void error(String title, String msg) {
        JOptionPane.showMessageDialog(frame.isVisible() ? frame : null, msg, title, JOptionPane.ERROR_MESSAGE);
    }

    /** Turns technical errors into something shop staff can act on. */
    static String friendly(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DocumentException) return t.getMessage();
            if (t instanceof FieryException fe) {
                if (fe.status() == 401 || fe.status() == 403) return "Fiery refused the login: check fiery.api-key, fiery.username and fiery.password in the config file. " + fe.getMessage();
                if (fe.getMessage().toLowerCase().contains("pdl")) return "Fiery cannot process this file type. Set image.mode=CONVERT_TO_PDF in the config file. " + fe.getMessage();
                return fe.getMessage();
            }
            if (t instanceof SSLException) return "Secure connection failed. Set fiery.trust-self-signed-certificate=true in the config file and restart. (" + t.getMessage() + ")";
            if (t instanceof ConnectException) return "Cannot reach the Fiery. Is it switched on and on the same network? Check fiery.base-url in the config file.";
            if (t instanceof HttpTimeoutException) return "The Fiery did not answer in time. Try again.";
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    static Image appIcon() {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(190, 30, 45));
        g.fillRoundRect(0, 0, 32, 32, 8, 8);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        g.drawString("C", 8, 24);
        g.dispose();
        return img;
    }

    private static ExecutorService daemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            Component comp = super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            if (!sel) {
                comp.setForeground(switch (String.valueOf(v)) {
                    case "Sent", "Printed" -> new Color(0, 120, 0);
                    case "Failed" -> Color.RED;
                    case "Skipped" -> Color.GRAY;
                    default -> new Color(0, 70, 160);
                });
            }
            return comp;
        }
    }
}
