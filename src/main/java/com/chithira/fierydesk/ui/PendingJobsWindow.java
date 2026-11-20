package com.chithira.fierydesk.ui;

import com.chithira.fierydesk.core.AppConfig;
import com.chithira.fierydesk.core.AppConfig.SendMethod;
import com.chithira.fierydesk.core.JobRequest;
import com.chithira.fierydesk.core.Sizing;
import com.chithira.fierydesk.document.DocumentNormalizer;
import com.chithira.fierydesk.document.FileKind;
import com.chithira.fierydesk.document.PageSpec;
import com.chithira.fierydesk.driver.DriverBatch;
import com.chithira.fierydesk.driver.DriverPrintOptions;
import com.chithira.fierydesk.driver.DriverPrinter;
import com.chithira.fierydesk.driver.PostScriptConverter;
import com.chithira.fierydesk.fiery.Paper;

import javax.print.PrintService;
import javax.print.attribute.standard.MediaSizeName;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Pops up when files arrive in the hot folder. Every waiting file has a tick box;
 * staff tick one or many, choose how to send them once, and send the batch:
 *
 *  - Fiery API: upload to the Hold queue, paper from the Fiery Paper Catalog
 *  - Printer driver: printed through the installed Fiery driver, exactly like Ctrl+P
 *
 * The selected row shows a preview; double-click (or "Open file") opens it in Windows.
 */
final class PendingJobsWindow extends JFrame {

    private static final Color GREEN = new Color(0, 120, 0), AMBER = new Color(190, 110, 0);

    /** One waiting file. */
    private static final class Entry {
        final Path file;
        final FileKind kind;
        final Dimension px;     // pixel size for images (incl. TIFF); null for PDF/PS or unreadable
        final long size;
        boolean checked = true;

        Entry(Path file) {
            this.file = file;
            this.kind = detectKind(file);
            this.px = (kind == FileKind.PDF || kind == FileKind.POSTSCRIPT) ? null
                    : DocumentNormalizer.imageSize(file).orElse(null);
            long s = 0;
            try { s = Files.size(file); } catch (IOException ignored) {}
            this.size = s;
        }
    }

    /** Paper size offered by the printer driver (null media = driver default). */
    private record DriverPaper(MediaSizeName media) {
        @Override public String toString() { return DriverPrinter.label(media); }
    }

    private AppConfig cfg;
    private final Consumer<List<JobRequest>> onSend;
    private final Consumer<DriverBatch> onDriverPrint;
    private final Consumer<List<Path>> onSkip;
    private final Runnable onRefreshPapers;

    private final List<Entry> entries = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);

    // how to send
    private final JComboBox<SendMethod> methodBox = new JComboBox<>(SendMethod.values());
    {
        methodBox.setPrototypeDisplayValue(SendMethod.API);
    }
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);

    // Fiery API options
    private final JComboBox<Paper> paperBox = new JComboBox<>();
    private final JLabel paperDetails = new JLabel(" ");
    private final JTextField jobName = new JTextField(18);
    private final JTextArea instructions = new JTextArea(3, 18);

    // Driver → API: which driver converts to PostScript
    private final JComboBox<String> converterBox = new JComboBox<>();
    private final JLabel converterLabel = new JLabel("Convert with:");

    // Printer driver options
    private final JComboBox<String> printerBox = new JComboBox<>();
    private final JComboBox<DriverPaper> driverPaperBox = new JComboBox<>();
    private final JCheckBox combineBox = new JCheckBox("Combine ticked images into one job");
    private final JCheckBox dialogBox = new JCheckBox("Show Windows print dialog (driver Properties)");

    // shared
    private final JComboBox<Sizing> sizingBox = new JComboBox<>(Sizing.values());
    private final JSpinner copies = new JSpinner(new SpinnerNumberModel(1, 1, 9999, 1));
    private final JCheckBox allowLowRes = new JCheckBox("Allow low resolution (customer accepts)");
    private final JButton sendButton = new JButton("Send");
    private final JButton skipButton = new JButton("Skip ticked");

    private final JLabel preview = new JLabel("", SwingConstants.CENTER);
    private final JLabel fileInfo = new JLabel(" ");
    private int previewToken;

    PendingJobsWindow(AppConfig cfg, Image icon,
                      Consumer<List<JobRequest>> onSend, Consumer<DriverBatch> onDriverPrint,
                      Consumer<List<Path>> onSkip, Runnable onRefreshPapers) {
        super("Files waiting for the Fiery");
        this.cfg = cfg;
        this.onSend = onSend;
        this.onDriverPrint = onDriverPrint;
        this.onSkip = onSkip;
        this.onRefreshPapers = onRefreshPapers;
        setIconImage(icon);
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        methodBox.setSelectedItem(cfg.sendMethod);
        sizingBox.setSelectedItem(cfg.lastSizing);
        combineBox.setSelected(cfg.driverCombineIntoOneJob);
        dialogBox.setSelected(cfg.driverShowPrintDialog);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);

        // keep long printer / paper names from widening the options panel
        Dimension combo = new Dimension(300, methodBox.getPreferredSize().height);
        for (JComboBox<?> b : List.of(methodBox, paperBox, printerBox, driverPaperBox, sizingBox, converterBox)) b.setPreferredSize(combo);

        setContentPane(buildContent());

        methodBox.addActionListener(e -> onMethodChanged());
        paperBox.addActionListener(e -> { updatePaperDetails(); refresh(); });
        printerBox.addActionListener(e -> loadDriverPapers());
        driverPaperBox.addActionListener(e -> refresh());
        sizingBox.addActionListener(e -> refresh());
        combineBox.addActionListener(e -> updateButtons());
        allowLowRes.addActionListener(e -> updateButtons());

        getRootPane().registerKeyboardAction(e -> setVisible(false),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        addWindowListener(new WindowAdapter() {
            @Override public void windowActivated(WindowEvent e) { setAlwaysOnTop(false); } // pop up once, then normal
        });

        onMethodChanged();
        loadPrinters();
        setSize(1150, 680);
        setLocationRelativeTo(null);
    }

    // ------------------------------------------------------------ public API

    void addFile(Path file) {
        for (Entry e : entries) if (e.file.equals(file)) return;
        entries.add(new Entry(file));
        int row = entries.size() - 1;
        model.fireTableRowsInserted(row, row);
        if (table.getSelectedRow() < 0) table.setRowSelectionInterval(row, row);
        updateButtons();
        popUp();
    }

    void removeFiles(List<Path> files) {
        entries.removeIf(e -> files.contains(e.file));
        model.fireTableDataChanged();
        if (!entries.isEmpty() && table.getSelectedRow() < 0) table.setRowSelectionInterval(0, 0);
        if (entries.isEmpty()) {
            showPreview(null);
            setVisible(false);
        }
        updateButtons();
    }

    int pendingCount() { return entries.size(); }

    /** Settings changed (DPI limits, image mode, driver defaults): re-evaluate the list. */
    void setConfig(AppConfig cfg) {
        this.cfg = cfg;
        refresh();
    }

    void setPapers(List<Paper> papers) {
        Paper current = (Paper) paperBox.getSelectedItem();
        String wanted = current != null ? current.id() : cfg.lastPaperId;
        paperBox.removeAllItems();
        for (Paper p : papers) paperBox.addItem(p);
        for (int i = 0; i < paperBox.getItemCount(); i++) {
            if (paperBox.getItemAt(i).id().equals(wanted)) { paperBox.setSelectedIndex(i); break; }
        }
        updatePaperDetails();
        refresh();
    }

    void popUp() {
        if (!isVisible()) setVisible(true);
        setAlwaysOnTop(true);
        setExtendedState(JFrame.NORMAL);
        toFront();
        Toolkit.getDefaultToolkit().beep();
    }

    // ------------------------------------------------------------ layout

    private JComponent buildContent() {
        table.setRowHeight(24);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.getColumnModel().getColumn(3).setPreferredWidth(100);
        table.getColumnModel().getColumn(4).setPreferredWidth(240);
        table.getColumnModel().getColumn(4).setCellRenderer(new QualityRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onRowSelected();
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.columnAtPoint(e.getPoint()) != 0) openSelected();
            }
        });

        JButton all = new JButton("Tick all");
        JButton none = new JButton("Untick all");
        JButton open = new JButton("Open file");
        all.addActionListener(e -> { entries.forEach(x -> x.checked = true); model.fireTableDataChanged(); updateButtons(); });
        none.addActionListener(e -> { entries.forEach(x -> x.checked = false); model.fireTableDataChanged(); updateButtons(); });
        open.addActionListener(e -> openSelected());

        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        listButtons.add(all);
        listButtons.add(none);
        listButtons.add(open);
        listButtons.add(new JLabel("  Double-click a file to view it"));

        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.add(listButtons, BorderLayout.NORTH);
        left.add(new JScrollPane(table), BorderLayout.CENTER);

        preview.setPreferredSize(new Dimension(300, 210));
        preview.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        JPanel previewPanel = new JPanel(new BorderLayout(4, 4));
        previewPanel.add(preview, BorderLayout.CENTER);
        previewPanel.add(fileInfo, BorderLayout.SOUTH);

        cardPanel.add(apiCard(), SendMethod.API.name());
        cardPanel.add(driverCard(), SendMethod.DRIVER.name());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();
        int r = 0;
        r = row(form, c, r, "Send via:", methodBox);
        c.gridx = 0; c.gridy = r++; c.gridwidth = 2; c.weightx = 1;
        form.add(cardPanel, c);
        c.gridwidth = 1;
        r = row(form, c, r, "Image sizing:", sizingBox);
        r = row(form, c, r, "Copies (each):", copies);
        row(form, c, r, "", allowLowRes);

        JPanel right = new JPanel(new BorderLayout(8, 8));
        right.add(previewPanel, BorderLayout.NORTH);
        right.add(form, BorderLayout.CENTER);

        sendButton.addActionListener(e -> sendTicked());
        skipButton.addActionListener(e -> skipTicked());
        getRootPane().setDefaultButton(sendButton);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(skipButton);
        buttons.add(sendButton);

        left.setMinimumSize(new Dimension(380, 200));
        right.setMinimumSize(new Dimension(470, 200));
        right.setPreferredSize(new Dimension(500, 560));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(1.0);   // extra width goes to the file list; the options keep their width

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(split, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        return root;
    }

    private JComponent apiCard() {
        JButton refresh = new JButton("↻");
        refresh.setToolTipText("Reload the paper catalog from the Fiery");
        refresh.addActionListener(e -> onRefreshPapers.run());
        JPanel paperRow = new JPanel(new BorderLayout(4, 0));
        paperRow.add(paperBox, BorderLayout.CENTER);
        paperRow.add(refresh, BorderLayout.EAST);
        paperDetails.setFont(paperDetails.getFont().deriveFont(Font.PLAIN, 11f));

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();
        int r = 0;
        c.gridx = 0; c.gridy = r; c.weightx = 0;
        p.add(converterLabel, c);
        c.gridx = 1; c.weightx = 1;
        p.add(converterBox, c);
        converterBox.setToolTipText("The Fiery driver converts the file to PostScript exactly like Ctrl+P, "
                + "but to a file; the app then uploads it with the API");
        r++;
        r = row(p, c, r, "Paper (catalog):", paperRow);
        r = row(p, c, r, "", paperDetails);
        r = row(p, c, r, "Queue:", new JLabel("Hold (release in Command WorkStation)"));
        r = row(p, c, r, "Job name:", jobName);
        row(p, c, r, "Instructions:", new JScrollPane(instructions));
        return p;
    }

    private JComponent driverCard() {
        JLabel note = new JLabel("<html><div style='width:300px'><small>Same path as Ctrl+P: each image is drawn "
                + "on the page and the installed Fiery driver turns it into PostScript for its printer queue "
                + "(use the Fiery <b>Hold</b> printer). PDFs print page by page.</small></div></html>");
        note.setForeground(Color.DARK_GRAY);

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();
        int r = 0;
        r = row(p, c, r, "Printer:", printerBox);
        r = row(p, c, r, "Paper size:", driverPaperBox);
        r = row(p, c, r, "", combineBox);
        r = row(p, c, r, "", dialogBox);
        row(p, c, r, "", note);
        return p;
    }

    private static GridBagConstraints gbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private static int row(JPanel p, GridBagConstraints c, int r, String label, JComponent f) {
        c.gridx = 0; c.gridy = r; c.weightx = 0;
        p.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1;
        p.add(f, c);
        return r + 1;
    }

    // ------------------------------------------------------------ printers (driver method)

    /** Installed printers, loaded in the background (can take a moment on Windows). */
    private void loadPrinters() {
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() {
                return DriverPrinter.printers().stream().map(PrintService::getName).toList();
            }
            @Override protected void done() {
                try {
                    List<String> names = get();
                    printerBox.removeAllItems();
                    names.forEach(printerBox::addItem);
                    String wanted = cfg.driverPrinterName;
                    converterBox.removeAllItems();
                    names.forEach(converterBox::addItem);
                    converterBox.addItem(PostScriptConverter.BUILT_IN);
                    String conv = names.stream().filter(n -> n.equalsIgnoreCase(wanted)).findFirst()
                            .orElse(names.stream().filter(n -> n.toLowerCase(Locale.ROOT).contains("fiery")).findFirst()
                                    .orElse(PostScriptConverter.BUILT_IN));
                    converterBox.setSelectedItem(conv);
                    String pick = names.stream().filter(n -> n.equalsIgnoreCase(wanted)).findFirst()
                            .orElse(names.stream().filter(n -> n.toLowerCase(Locale.ROOT).contains("fiery")).findFirst()
                                    .orElse(names.isEmpty() ? null : names.get(0)));
                    if (pick != null) printerBox.setSelectedItem(pick);
                    loadDriverPapers();
                } catch (Exception ignored) {
                    // no printers: the driver method stays disabled
                }
            }
        }.execute();
    }

    /** Paper sizes the selected printer's driver offers, like the Ctrl+P dialog. */
    private void loadDriverPapers() {
        String printer = (String) printerBox.getSelectedItem();
        if (printer == null) return;
        new SwingWorker<List<MediaSizeName>, Void>() {
            @Override protected List<MediaSizeName> doInBackground() {
                return DriverPrinter.find(printer).map(DriverPrinter::paperSizes).orElse(List.of());
            }
            @Override protected void done() {
                try {
                    List<MediaSizeName> sizes = get();
                    driverPaperBox.removeAllItems();
                    driverPaperBox.addItem(new DriverPaper(null));
                    sizes.forEach(m -> driverPaperBox.addItem(new DriverPaper(m)));
                    for (int i = 0; i < driverPaperBox.getItemCount(); i++) {
                        MediaSizeName m = driverPaperBox.getItemAt(i).media();
                        if (m != null && m.toString().equalsIgnoreCase(cfg.driverPaper)) { driverPaperBox.setSelectedIndex(i); break; }
                    }
                    refresh();
                } catch (Exception ignored) {
                    // keep whatever was there
                }
            }
        }.execute();
    }

    // ------------------------------------------------------------ actions

    private SendMethod method() {
        SendMethod m = (SendMethod) methodBox.getSelectedItem();
        return m == null ? SendMethod.API : m;
    }

    private Sizing sizing() {
        Sizing s = (Sizing) sizingBox.getSelectedItem();
        return s == null ? Sizing.FIT : s;
    }

    private void onMethodChanged() {
        cards.show(cardPanel, method() == SendMethod.DRIVER ? SendMethod.DRIVER.name() : SendMethod.API.name());
        boolean conv = method() == SendMethod.DRIVER_API;
        converterLabel.setVisible(conv);
        converterBox.setVisible(conv);
        refresh();
    }

    private void sendTicked() {
        List<Entry> ticked = ticked();
        if (ticked.isEmpty()) return;

        if (method() != SendMethod.DRIVER && paperBox.getSelectedItem() == null) {
            JOptionPane.showMessageDialog(this, "Choose a paper first. If the list is empty, click ↻ to load the paper catalog.");
            return;
        }
        if (method() == SendMethod.DRIVER && printerBox.getSelectedItem() == null) {
            JOptionPane.showMessageDialog(this, "No printer found. Install the Fiery printer driver and add the Fiery Hold queue as a printer.");
            return;
        }

        List<String> tooLow = new ArrayList<>();
        for (Entry e : ticked) {
            Integer dpi = dpiOf(e);
            if (dpi != null && dpi < cfg.minDpi) tooLow.add(e.file.getFileName() + " (" + dpi + " DPI)");
        }
        if (!tooLow.isEmpty() && !allowLowRes.isSelected()) {
            JOptionPane.showMessageDialog(this, "These files are too low resolution for this paper:\n\n"
                            + String.join("\n", tooLow)
                            + "\n\nUntick them, choose 'Actual size', or tick 'Allow low resolution'.",
                    "Low resolution", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Sizing sizing = sizing();
        int n = (Integer) copies.getValue();
        List<Path> files = ticked.stream().map(e -> e.file).toList();
        cfg.lastSizing = sizing;
        cfg.sendMethod = method();

        if (method() == SendMethod.DRIVER) {
            String printer = (String) printerBox.getSelectedItem();
            DriverPaper paper = (DriverPaper) driverPaperBox.getSelectedItem();
            MediaSizeName media = paper == null ? null : paper.media();
            cfg.driverPrinterName = printer;
            cfg.driverPaper = media == null ? "" : media.toString();
            DriverPrintOptions o = new DriverPrintOptions(printer, media, sizing, n,
                    combineBox.isSelected(), dialogBox.isSelected());
            resetForm();
            removeFiles(files);
            onDriverPrint.accept(new DriverBatch(files, o));
            return;
        }

        Paper paper = (Paper) paperBox.getSelectedItem();
        String converter = method() == SendMethod.DRIVER_API ? (String) converterBox.getSelectedItem() : null;
        if (method() == SendMethod.DRIVER_API && converter == null) converter = PostScriptConverter.BUILT_IN;
        if (converter != null && !PostScriptConverter.BUILT_IN.equals(converter)) cfg.driverPrinterName = converter;
        boolean single = ticked.size() == 1;
        List<JobRequest> requests = new ArrayList<>();
        for (Entry e : ticked) {
            String name = single && !jobName.getText().isBlank()
                    ? jobName.getText().trim()
                    : DocumentNormalizer.safeName(e.file.getFileName().toString());
            requests.add(new JobRequest(e.file, name, paper, sizing, n,
                    instructions.getText().trim(), allowLowRes.isSelected(), converter));
        }
        cfg.lastPaperId = paper.id();
        resetForm();
        removeFiles(files);
        onSend.accept(requests);
    }

    private void resetForm() {
        allowLowRes.setSelected(false);
        instructions.setText("");
        copies.setValue(1);
    }

    private void skipTicked() {
        List<Path> files = ticked().stream().map(e -> e.file).toList();
        if (files.isEmpty()) return;
        int ok = JOptionPane.showConfirmDialog(this, "Skip " + files.size() + " file(s)? They will be moved to the Skipped folder.",
                "Skip", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;
        removeFiles(files);
        onSkip.accept(files);
    }

    private void openSelected() {
        Entry e = selected();
        if (e == null) return;
        try {
            Desktop.getDesktop().open(e.file.toFile());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Cannot open the file: " + ex.getMessage());
        }
    }

    private void onRowSelected() {
        Entry e = selected();
        showPreview(e);
        if (e != null && ticked().size() <= 1) jobName.setText(DocumentNormalizer.safeName(e.file.getFileName().toString()));
    }

    private void refresh() {
        model.fireTableDataChanged();
        updateButtons();
    }

    private void updateButtons() {
        List<Entry> t = ticked();
        boolean driver = method() == SendMethod.DRIVER;
        String what = t.size() <= 1 ? "" : t.size() + " files ";
        if (driver) {
            sendButton.setText(combineBox.isSelected() && t.size() > 1
                    ? "Print " + t.size() + " files as one job (driver)"
                    : "Print " + what + "(driver, like Ctrl+P)");
            sendButton.setEnabled(!t.isEmpty() && printerBox.getSelectedItem() != null);
        } else if (method() == SendMethod.DRIVER_API) {
            sendButton.setText("Convert " + what + "to PostScript & send (Hold)");
            sendButton.setEnabled(!t.isEmpty() && paperBox.getSelectedItem() != null);
        } else {
            sendButton.setText("Send " + what + "to Fiery (Hold)");
            sendButton.setEnabled(!t.isEmpty() && paperBox.getSelectedItem() != null);
        }
        skipButton.setEnabled(!t.isEmpty());
        jobName.setEnabled(t.size() <= 1);
        if (t.size() > 1) jobName.setText("(each file uses its own name)");
        else if (t.size() == 1) jobName.setText(DocumentNormalizer.safeName(t.get(0).file.getFileName().toString()));
    }

    private void updatePaperDetails() {
        Paper p = (Paper) paperBox.getSelectedItem();
        paperDetails.setText(p == null ? "No paper catalog loaded" : p.details());
    }

    // ------------------------------------------------------------ preview

    private void showPreview(Entry e) {
        int token = ++previewToken;
        preview.setIcon(null);
        if (e == null) { preview.setText(""); fileInfo.setText(" "); return; }
        preview.setText("Loading preview…");
        StringBuilder info = new StringBuilder("<html>").append(e.kind.label());
        if (e.px != null) info.append(", ").append(e.px.width).append(" × ").append(e.px.height).append(" px");
        info.append(", ").append(humanSize(e.size)).append("</html>");
        fileInfo.setText(info.toString());

        new SwingWorker<Optional<BufferedImage>, Void>() {
            @Override protected Optional<BufferedImage> doInBackground() {
                return DocumentNormalizer.thumbnail(e.file, e.kind, 300);
            }
            @Override protected void done() {
                if (token != previewToken) return; // another row was selected meanwhile
                try {
                    Optional<BufferedImage> img = get();
                    if (img.isPresent()) {
                        BufferedImage b = img.get();
                        double s = Math.min(290.0 / b.getWidth(), 220.0 / b.getHeight());
                        preview.setIcon(new ImageIcon(b.getScaledInstance(
                                (int) Math.max(1, b.getWidth() * s), (int) Math.max(1, b.getHeight() * s), Image.SCALE_SMOOTH)));
                        preview.setText(null);
                    } else {
                        preview.setText(e.kind.label() + " – no preview, double-click to open");
                    }
                } catch (Exception ex) {
                    preview.setText("No preview");
                }
            }
        }.execute();
    }

    // ------------------------------------------------------------ quality

    /** Page the image will land on, for the chosen method and paper. */
    private PageSpec currentSpec() {
        if (method() == SendMethod.DRIVER) {
            DriverPaper dp = (DriverPaper) driverPaperBox.getSelectedItem();
            double[] inches = dp == null ? null : DriverPrinter.inches(dp.media()).orElse(null);
            return sizing().pageSpec(inches);
        }
        return sizing().pageSpec((Paper) paperBox.getSelectedItem());
    }

    /** True when the image is drawn/placed on a page (so DPI matters). */
    private boolean isPlaced(Entry e) {
        if (e.px == null) return false;
        if (method() != SendMethod.API) return true;                          // driver methods: every image is drawn
        return !e.kind.isPrintReady() && cfg.imageMode == AppConfig.ImageMode.CONVERT_TO_PDF;
    }

    private Integer dpiOf(Entry e) {
        if (!isPlaced(e)) return null;
        return currentSpec().layout(e.px.width, e.px.height).dpi();
    }

    private String qualityText(Entry e) {
        if (method() == SendMethod.DRIVER_API) {
            if (e.kind == FileKind.PDF) return "PDF → PostScript via driver";
            if (e.kind == FileKind.POSTSCRIPT) return "PostScript, sent as is";
            if (e.px == null) return "Unreadable image";
        } else if (method() == SendMethod.DRIVER) {
            if (e.kind == FileKind.PDF) return "PDF – printed page by page";
            if (e.kind == FileKind.POSTSCRIPT) return "PostScript – use the Fiery API method";
            if (e.px == null) return "Unreadable image";
        } else {
            if (e.kind.isPrintReady()) return "Print-ready, sent as is";
            if (e.px == null) return "Unreadable image";
            if (cfg.imageMode == AppConfig.ImageMode.SEND_ORIGINAL) return "Sent unconverted";
        }
        PageSpec.Layout l = currentSpec().layout(e.px.width, e.px.height);
        StringBuilder sb = new StringBuilder().append(l.dpi()).append(" DPI");
        if (method() == SendMethod.DRIVER && driverPaperBox.getSelectedItem() != null
                && ((DriverPaper) driverPaperBox.getSelectedItem()).media() == null && sizing() != Sizing.ACTUAL) {
            sb.append(" (at image size; driver default paper)");
        }
        if (l.rotate()) sb.append(", rotated");
        if (l.cropped()) sb.append(", cropped");
        if (l.margins()) sb.append(", margins");
        return sb.toString();
    }

    private Color qualityColor(Entry e) {
        if (method() == SendMethod.DRIVER && e.kind == FileKind.POSTSCRIPT) return Color.RED;
        if (e.px == null) return (e.kind == FileKind.PDF || e.kind.isPrintReady()) ? GREEN : Color.RED;
        Integer dpi = dpiOf(e);
        if (dpi == null) return e.kind.isPrintReady() ? GREEN : AMBER;
        return dpi < cfg.minDpi ? Color.RED : dpi < cfg.warnDpi ? AMBER : GREEN;
    }

    // ------------------------------------------------------------ helpers

    private List<Entry> ticked() { return entries.stream().filter(x -> x.checked).toList(); }

    private Entry selected() {
        int r = table.getSelectedRow();
        return r < 0 || r >= entries.size() ? null : entries.get(table.convertRowIndexToModel(r));
    }

    private static FileKind detectKind(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return FileKind.detect(in.readNBytes(8));
        } catch (IOException e) {
            return FileKind.UNKNOWN;
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private final class Model extends AbstractTableModel {
        private final String[] cols = {"Send", "File", "Type", "Size", "Quality on chosen paper"};
        @Override public int getRowCount() { return entries.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }
        @Override public Object getValueAt(int r, int c) {
            Entry e = entries.get(r);
            return switch (c) {
                case 0 -> e.checked;
                case 1 -> e.file.getFileName().toString();
                case 2 -> e.kind.label();
                case 3 -> e.px != null ? e.px.width + "×" + e.px.height : humanSize(e.size);
                default -> qualityText(e);
            };
        }
        @Override public void setValueAt(Object v, int r, int c) {
            if (c == 0) { entries.get(r).checked = Boolean.TRUE.equals(v); updateButtons(); }
        }
    }

    private final class QualityRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            Component comp = super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            if (!sel && r < entries.size()) comp.setForeground(qualityColor(entries.get(t.convertRowIndexToModel(r))));
            return comp;
        }
    }
}
