package com.chithira.fierydesk.ui;

import com.chithira.fierydesk.core.AppConfig;
import com.chithira.fierydesk.core.AppConfig.SendMethod;
import com.chithira.fierydesk.core.JobRequest;
import com.chithira.fierydesk.core.SheetJob;
import com.chithira.fierydesk.core.SheetMaker;
import com.chithira.fierydesk.core.Sizing;
import com.chithira.fierydesk.document.DocumentNormalizer;
import com.chithira.fierydesk.document.FileKind;
import com.chithira.fierydesk.document.FilePageSize;
import com.chithira.fierydesk.document.PageSpec;
import com.chithira.fierydesk.driver.DriverBatch;
import com.chithira.fierydesk.driver.DriverPrintOptions;
import com.chithira.fierydesk.driver.DriverPrinter;
import com.chithira.fierydesk.driver.PostScriptConverter;
import com.chithira.fierydesk.fiery.Paper;
import com.chithira.fierydesk.hub.HubConnection;
import com.chithira.fierydesk.hub.HubCustomer;
import com.chithira.fierydesk.core.CustomerRef;
import com.chithira.fierydesk.impose.AutoSize;
import com.chithira.fierydesk.impose.ImposeSettings;
import com.chithira.fierydesk.impose.Orientation;
import com.chithira.fierydesk.impose.Preset;
import com.chithira.fierydesk.impose.SheetLayout;
import com.chithira.fierydesk.impose.SheetPreview;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pops up when files arrive in the hot folder. Staff tick one or many files, choose how to
 * send them once, and send the batch. Images can be imposed (step and repeat, crop marks)
 * and paired as front + back; the preview shows the real sheet(s).
 */
final class PendingJobsWindow extends JFrame {

    private static final Color GREEN = new Color(0, 120, 0), AMBER = new Color(190, 110, 0);

    /** One waiting file, or a front + back pair. */
    private static final class Entry {
        final Path file;
        final Path back;           // null unless paired
        final FileKind kind;
        final Dimension px;        // pixel size for images (incl. TIFF); null for PDF/PS or unreadable
        final long size;
        boolean checked = true;
        AutoSize.Guess guess;      // lazily computed

        Entry(Path file, Path back) {
            this.file = file;
            this.back = back;
            this.kind = detectKind(file);
            this.px = (kind == FileKind.PDF || kind == FileKind.POSTSCRIPT) ? null
                    : DocumentNormalizer.imageSize(file).orElse(null);
            long s = 0;
            try { s = Files.size(file) + (back == null ? 0 : Files.size(back)); } catch (IOException ignored) {}
            this.size = s;
        }

        boolean isImage() { return px != null; }
        List<Path> files() { return back == null ? List.of(file) : List.of(file, back); }
    }

    /** Paper size offered by the printer driver (null media = driver default). */
    private record DriverPaper(MediaSizeName media) {
        @Override public String toString() { return DriverPrinter.label(media); }
    }

    /** Piece size choice: automatic per image, a preset, or a custom size. */
    private record SizeChoice(String kind, Preset preset) {
        @Override public String toString() {
            return switch (kind) {
                case "AUTO" -> "Auto – from each image";
                case "CUSTOM" -> "Custom size…";
                default -> preset.toString();
            };
        }
    }

    private AppConfig cfg;
    private final Consumer<List<JobRequest>> onSend;
    private final Consumer<DriverBatch> onDriverPrint;
    private final Consumer<List<Path>> onSkip;
    private final Runnable onRefreshPapers;

    // Print Hub customer for the whole batch
    private final HubConnection hub;
    private final CustomerPicker customerPicker;
    private final JLabel customerLabel = new JLabel("Customer:");

    private final List<Entry> entries = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);

    // how to send
    private final JComboBox<SendMethod> methodBox = new JComboBox<>(SendMethod.values());
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);

    // Fiery API options
    private final JComboBox<Paper> paperBox = new JComboBox<>();
    private final JLabel paperDetails = new JLabel(" ");
    private final JTextField jobName = new JTextField(18);
    private final JTextArea instructions = new JTextArea(2, 18);
    private final JComboBox<String> converterBox = new JComboBox<>();
    private final JLabel converterLabel = new JLabel("Convert with:");

    // Printer driver options
    private final JComboBox<String> printerBox = new JComboBox<>();
    private final JComboBox<DriverPaper> driverPaperBox = new JComboBox<>();
    private final JCheckBox combineBox = new JCheckBox("Combine ticked images into one job");
    private final JCheckBox dialogBox = new JCheckBox("Show Windows print dialog (driver Properties)");

    // imposition
    private final JCheckBox imposeBox = new JCheckBox("Impose – fill the sheet with copies (crop marks on)");
    private final JComboBox<SizeChoice> sizeBox = new JComboBox<>();
    private final JSpinner customW = mmSpinner(0, 0, 2000);
    private final JSpinner customH = mmSpinner(0, 0, 2000);
    private final JPanel customRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JLabel guessLabel = new JLabel(" ");
    private final JSpinner marginMm = mmSpinner(0, 0, 50);
    private final JSpinner gapMm = mmSpinner(0, 0, 50);
    private final JSpinner bleedMm = mmSpinner(0, 0, 10);
    private final JSpinner cols = new JSpinner(new SpinnerNumberModel(0, 0, 99, 1));
    private final JSpinner rows = new JSpinner(new SpinnerNumberModel(0, 0, 99, 1));
    private final JSpinner quantity = new JSpinner(new SpinnerNumberModel(0, 0, 1_000_000, 1));
    private final JComboBox<ImposeSettings.Mode> modeBox = new JComboBox<>(ImposeSettings.Mode.values());
    private final JComboBox<Orientation> orientationBox = new JComboBox<>(Orientation.values());
    private final JLabel gridHint = new JLabel("(0 = automatic)");
    private final JLabel summary = new JLabel(" ");
    private final JPanel imposePanel = new JPanel(new GridBagLayout());

    // shared
    private final JComboBox<Sizing> sizingBox = new JComboBox<>(Sizing.values());
    private final JSpinner copies = new JSpinner(new SpinnerNumberModel(1, 1, 9999, 1));
    private final JLabel sizingLabel = new JLabel("Image sizing:");
    private final JLabel copiesLabel = new JLabel("Copies (each):");
    private final JCheckBox allowLowRes = new JCheckBox("Allow low resolution (customer accepts)");
    private final JButton sendButton = new JButton("Send");
    private final JButton skipButton = new JButton("Skip ticked");
    private final JButton pairButton = new JButton("Pair as Front / Back");
    private final JButton swapButton = new JButton("⇄ Swap");
    private final JButton unpairButton = new JButton("Unpair");

    private final JLabel preview = new JLabel("", SwingConstants.CENTER);
    private final JLabel fileInfo = new JLabel(" ");
    private final Map<Path, BufferedImage> thumbs = new ConcurrentHashMap<>();
    private int previewToken;
    private boolean updating;

    PendingJobsWindow(AppConfig cfg, Image icon, HubConnection hub,
                      Consumer<List<JobRequest>> onSend, Consumer<DriverBatch> onDriverPrint,
                      Consumer<List<Path>> onSkip, Runnable onRefreshPapers) {
        super("Files waiting for the Fiery");
        this.cfg = cfg;
        this.hub = hub;
        this.customerPicker = new CustomerPicker(hub);
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
        applyImposeDefaults();
        imposeBox.setSelected(cfg.lastImpose);
        modeBox.setSelectedItem(ImposeSettings.Mode.FIT_GRID);
        orientationBox.setSelectedItem(cfg.imposeOrientation);

        Dimension combo = new Dimension(290, methodBox.getPreferredSize().height);
        for (JComboBox<?> b : List.of(methodBox, paperBox, printerBox, driverPaperBox, sizingBox, converterBox, sizeBox, modeBox, orientationBox)) {
            b.setPreferredSize(combo);
        }

        setContentPane(buildContent());
        updateCustomerRow();

        methodBox.addActionListener(e -> onMethodChanged());
        paperBox.addActionListener(e -> { updatePaperDetails(); refresh(); });
        printerBox.addActionListener(e -> loadDriverPapers());
        driverPaperBox.addActionListener(e -> refresh());
        sizingBox.setToolTipText("Actual size: the page is the file's own size (image at its stored DPI, else 300; PDF: its page) "
                + "and the Fiery is told that page size. Fit and Fill place images on the chosen paper.");
        sizingBox.addActionListener(e -> refresh());
        combineBox.addActionListener(e -> updateButtons());
        allowLowRes.addActionListener(e -> updateButtons());
        imposeBox.addActionListener(e -> onImposeToggled());
        sizeBox.addActionListener(e -> { customRow.setVisible(isCustom()); refresh(); });
        modeBox.addActionListener(e -> { updateGridHint(); refresh(); });
        orientationBox.addActionListener(e -> {
            cfg.imposeOrientation = (Orientation) orientationBox.getSelectedItem();
            refresh();
        });
        for (JSpinner s : List.of(customW, customH, marginMm, gapMm, bleedMm, cols, rows, quantity, copies)) {
            s.addChangeListener(e -> refresh());
        }

        getRootPane().registerKeyboardAction(e -> setVisible(false),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        addWindowListener(new WindowAdapter() {
            @Override public void windowActivated(WindowEvent e) { setAlwaysOnTop(false); } // pop up once, then normal
        });

        onMethodChanged();
        onImposeToggled();
        loadPrinters();
        setSize(1280, 760);
        setLocationRelativeTo(null);
    }

    // ================================================================== public API

    void addFile(Path file) {
        for (Entry e : entries) if (e.file.equals(file) || file.equals(e.back)) return;
        Entry added = new Entry(file, null);
        if (cfg.autoPairFrontBack && added.isImage() && autoPair(added)) {
            updateButtons();
            popUp();
            return;
        }
        entries.add(added);
        int row = entries.size() - 1;
        model.fireTableRowsInserted(row, row);
        if (table.getSelectedRow() < 0) table.setRowSelectionInterval(row, row);
        updateButtons();
        popUp();
    }

    void removeFiles(List<Path> files) {
        entries.removeIf(e -> files.contains(e.file) || (e.back != null && files.contains(e.back)));
        files.forEach(thumbs::remove);
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
        applyImposeDefaults();
        updateCustomerRow();
        refresh();
    }

    /** The Customer row is shown only when the Print Hub is switched on. */
    void updateCustomerRow() {
        boolean visible = hub.isEnabled();
        customerLabel.setVisible(visible);
        customerPicker.setVisible(visible);
        customerLabel.setText(hub.isCustomerRequired() ? "Customer *:" : "Customer:");
    }

    /** The customer chosen for this batch, or null. */
    private CustomerRef chosenCustomer() {
        HubCustomer customer = hub.isEnabled() ? customerPicker.selected() : null;
        return customer == null ? null : customer.toRef();
    }

    void setPapers(List<Paper> papers) {
        Paper current = (Paper) paperBox.getSelectedItem();
        String wanted = current != null ? current.id()
                : (imposeBox.isSelected() && !cfg.imposeDefaultPaperId.isBlank() ? cfg.imposeDefaultPaperId : cfg.lastPaperId);
        updating = true;
        paperBox.removeAllItems();
        for (Paper p : papers) paperBox.addItem(p);
        selectPaper(wanted);
        updating = false;
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

    // ================================================================== layout

    private JComponent buildContent() {
        table.setRowHeight(24);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.getColumnModel().getColumn(3).setPreferredWidth(90);
        table.getColumnModel().getColumn(4).setPreferredWidth(220);
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
        all.addActionListener(e -> { entries.forEach(x -> x.checked = true); refresh(); });
        none.addActionListener(e -> { entries.forEach(x -> x.checked = false); refresh(); });
        open.addActionListener(e -> openSelected());
        pairButton.addActionListener(e -> pairTicked());
        swapButton.addActionListener(e -> swapSelected());
        unpairButton.addActionListener(e -> unpairSelected());
        pairButton.setToolTipText("Tick exactly two images, then click to make them one front + back job");

        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (JButton b : List.of(all, none, open, pairButton, swapButton, unpairButton)) listButtons.add(b);

        JLabel hint = new JLabel("  Double-click a file to view it. Front/back pairs are made automatically from names like card_front / card_back.");
        hint.setForeground(Color.DARK_GRAY);
        hint.setFont(hint.getFont().deriveFont(11f));

        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.add(listButtons, BorderLayout.NORTH);
        left.add(new JScrollPane(table), BorderLayout.CENTER);
        left.add(hint, BorderLayout.SOUTH);

        preview.setPreferredSize(new Dimension(470, 300));
        preview.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        JPanel previewPanel = new JPanel(new BorderLayout(4, 4));
        previewPanel.add(preview, BorderLayout.CENTER);
        previewPanel.add(fileInfo, BorderLayout.SOUTH);

        cardPanel.add(apiCard(), SendMethod.API.name());
        cardPanel.add(driverCard(), SendMethod.DRIVER.name());
        buildImposePanel();

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();
        int r = 0;
        r = row(form, c, r, customerLabel, customerPicker);   // who the job is for (Print Hub)
        r = full(form, c, r, imposePanel);            // most-used first: impose + quantity
        r = full(form, c, r, new JSeparator());
        r = row(form, c, r, new JLabel("Send via:"), methodBox);
        r = full(form, c, r, cardPanel);
        r = full(form, c, r, new JSeparator());
        r = row(form, c, r, sizingLabel, sizingBox);
        r = row(form, c, r, copiesLabel, copies);
        row(form, c, r, new JLabel(""), allowLowRes);

        JPanel right = new ScrollablePanel(new BorderLayout(8, 8));
        right.add(previewPanel, BorderLayout.NORTH);
        right.add(form, BorderLayout.CENTER);
        JScrollPane rightScroll = new JScrollPane(right, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        rightScroll.setBorder(null);
        rightScroll.getVerticalScrollBar().setUnitIncrement(16);

        sendButton.addActionListener(e -> sendTicked());
        skipButton.addActionListener(e -> skipTicked());
        getRootPane().setDefaultButton(sendButton);
        sendButton.setFont(sendButton.getFont().deriveFont(Font.BOLD));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(skipButton);
        buttons.add(sendButton);

        left.setMinimumSize(new Dimension(420, 200));
        rightScroll.setMinimumSize(new Dimension(520, 200));
        rightScroll.setPreferredSize(new Dimension(540, 600));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, rightScroll);
        split.setResizeWeight(1.0);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(split, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        return root;
    }

    private void buildImposePanel() {
        imposeBox.setFont(imposeBox.getFont().deriveFont(Font.BOLD));
        customRow.add(customW);
        customRow.add(new JLabel("×"));
        customRow.add(customH);
        customRow.add(new JLabel("mm"));
        customRow.setVisible(false);
        guessLabel.setFont(guessLabel.getFont().deriveFont(Font.PLAIN, 11f));
        guessLabel.setForeground(Color.DARK_GRAY);
        summary.setFont(summary.getFont().deriveFont(Font.BOLD, 13f));

        JPanel spacing = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        spacing.add(new JLabel("Margin"));
        spacing.add(marginMm);
        spacing.add(new JLabel("Gap"));
        spacing.add(gapMm);
        spacing.add(new JLabel("Bleed"));
        spacing.add(bleedMm);
        spacing.add(new JLabel("mm"));

        JPanel grid = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        grid.add(new JLabel("Columns"));
        grid.add(cols);
        grid.add(new JLabel("Rows"));
        grid.add(rows);
        gridHint.setForeground(Color.GRAY);
        grid.add(gridHint);

        GridBagConstraints c = gbc();
        int r = 0;
        c.gridx = 0; c.gridy = r++; c.gridwidth = 2;
        imposePanel.add(imposeBox, c);
        c.gridwidth = 1;
        r = row(imposePanel, c, r, new JLabel("Sheet:"), orientationBox);
        r = row(imposePanel, c, r, new JLabel("Piece size:"), sizeBox);
        r = row(imposePanel, c, r, new JLabel("Layout:"), modeBox);
        r = row(imposePanel, c, r, new JLabel(""), customRow);
        r = row(imposePanel, c, r, new JLabel(""), guessLabel);
        r = row(imposePanel, c, r, new JLabel("Spacing:"), spacing);
        r = row(imposePanel, c, r, new JLabel("Grid:"), grid);
        r = row(imposePanel, c, r, new JLabel("Quantity (pieces):"), quantity);
        row(imposePanel, c, r, new JLabel(""), summary);
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
        r = row(p, c, r, converterLabel, converterBox);
        converterBox.setToolTipText("The Fiery driver converts the file to PostScript exactly like Ctrl+P, "
                + "but to a file; the app then uploads it with the API");
        r = row(p, c, r, new JLabel("Paper (catalog):"), paperRow);
        r = row(p, c, r, new JLabel(""), paperDetails);
        r = row(p, c, r, new JLabel("Queue:"), new JLabel("Hold (release in Command WorkStation)"));
        r = row(p, c, r, new JLabel("Job name:"), jobName);
        row(p, c, r, new JLabel("Instructions:"), new JScrollPane(instructions));
        return p;
    }

    private JComponent driverCard() {
        JLabel note = new JLabel("<html><div style='width:300px'><small>Same path as Ctrl+P: each image is drawn "
                + "on the page and the installed Fiery driver turns it into PostScript for its printer queue "
                + "(use the Fiery <b>Hold</b> printer). Front/back jobs print two-sided.</small></div></html>");
        note.setForeground(Color.DARK_GRAY);
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();
        int r = 0;
        r = row(p, c, r, new JLabel("Printer:"), printerBox);
        r = row(p, c, r, new JLabel("Paper size:"), driverPaperBox);
        r = row(p, c, r, new JLabel(""), combineBox);
        r = row(p, c, r, new JLabel(""), dialogBox);
        row(p, c, r, new JLabel(""), note);
        return p;
    }

    private static GridBagConstraints gbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private static int row(JPanel p, GridBagConstraints c, int r, JComponent label, JComponent f) {
        c.gridx = 0; c.gridy = r; c.weightx = 0; c.gridwidth = 1;
        p.add(label, c);
        c.gridx = 1; c.weightx = 1;
        p.add(f, c);
        return r + 1;
    }

    private static int full(JPanel p, GridBagConstraints c, int r, JComponent f) {
        c.gridx = 0; c.gridy = r; c.gridwidth = 2; c.weightx = 1;
        p.add(f, c);
        c.gridwidth = 1;
        return r + 1;
    }

    private static JSpinner mmSpinner(double value, double min, double max) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(value, min, max, 0.5));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(4);
        return s;
    }

    /** Lets the options panel scroll vertically but always fit the width. */
    private static final class ScrollablePanel extends JPanel implements Scrollable {
        ScrollablePanel(LayoutManager l) { super(l); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    // ================================================================== printers (driver method)

    private void loadPrinters() {
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() {
                return DriverPrinter.printers().stream().map(PrintService::getName).toList();
            }
            @Override protected void done() {
                try {
                    List<String> names = get();
                    String wanted = cfg.driverPrinterName;
                    printerBox.removeAllItems();
                    names.forEach(printerBox::addItem);
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
                    // no printers: the driver methods stay disabled
                }
            }
        }.execute();
    }

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

    // ================================================================== front / back pairing

    private static final Pattern SIDE = Pattern.compile(
            "^(.*?)[ _.\\-]*(front|back|f|b|side[ _\\-]?1|side[ _\\-]?2)$", Pattern.CASE_INSENSITIVE);

    /** {base name, "front"/"back"} from names like card_front, card-B, card side2; null if no side in the name. */
    private static String[] side(Path file) {
        String base = DocumentNormalizer.safeName(file.getFileName().toString()).toLowerCase(Locale.ROOT);
        Matcher m = SIDE.matcher(base);
        if (!m.matches() || m.group(1).isBlank()) return null;
        String s = m.group(2).replaceAll("[ _\\-]", "");
        boolean front = s.equals("front") || s.equals("f") || s.equals("side1");
        return new String[]{m.group(1).trim(), front ? "front" : "back"};
    }

    /** Pairs a newly arrived image with a waiting one when their names say front/back. */
    private boolean autoPair(Entry added) {
        String[] a = side(added.file);
        if (a == null) return false;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.back != null || !e.isImage()) continue;
            String[] b = side(e.file);
            if (b == null || !b[0].equals(a[0]) || b[1].equals(a[1])) continue;
            Entry pair = a[1].equals("front") ? new Entry(added.file, e.file) : new Entry(e.file, added.file);
            entries.set(i, pair);
            model.fireTableDataChanged();
            table.setRowSelectionInterval(i, i);
            return true;
        }
        return false;
    }

    private void pairTicked() {
        List<Entry> t = ticked();
        if (t.size() != 2 || t.stream().anyMatch(e -> e.back != null || !e.isImage())) {
            JOptionPane.showMessageDialog(this, "Tick exactly two image files (not already paired) to make a front + back job.");
            return;
        }
        Entry first = t.get(0), second = t.get(1);
        String[] s1 = side(first.file);
        boolean firstIsBack = s1 != null && s1[1].equals("back");
        Entry pair = firstIsBack ? new Entry(second.file, first.file) : new Entry(first.file, second.file);
        entries.remove(second);
        int index = entries.indexOf(first);
        entries.set(index, pair);
        model.fireTableDataChanged();
        table.setRowSelectionInterval(index, index);
        refresh();
    }

    private void swapSelected() {
        Entry e = selected();
        if (e == null || e.back == null) return;
        int i = entries.indexOf(e);
        entries.set(i, new Entry(e.back, e.file));
        model.fireTableDataChanged();
        table.setRowSelectionInterval(i, i);
        refresh();
    }

    private void unpairSelected() {
        Entry e = selected();
        if (e == null || e.back == null) return;
        int i = entries.indexOf(e);
        entries.set(i, new Entry(e.file, null));
        entries.add(i + 1, new Entry(e.back, null));
        model.fireTableDataChanged();
        table.setRowSelectionInterval(i, i);
        refresh();
    }

    // ================================================================== imposition

    private void applyImposeDefaults() {
        updating = true;
        Object current = sizeBox.getSelectedItem();
        sizeBox.removeAllItems();
        sizeBox.addItem(new SizeChoice("AUTO", null));
        for (Preset p : Preset.parse(cfg.imposePresets)) sizeBox.addItem(new SizeChoice("PRESET", p));
        sizeBox.addItem(new SizeChoice("CUSTOM", null));
        if (current != null) sizeBox.setSelectedItem(current);
        if (sizeBox.getSelectedIndex() < 0) sizeBox.setSelectedIndex(0);
        marginMm.setValue(cfg.imposeMarginMm);
        gapMm.setValue(cfg.imposeGapMm);
        bleedMm.setValue(cfg.imposeBleedMm);
        updating = false;
    }

    private void onImposeToggled() {
        boolean on = imposeBox.isSelected();
        for (Component comp : imposePanel.getComponents()) if (comp != imposeBox) comp.setVisible(on);
        customRow.setVisible(on && isCustom());
        // when imposing, quantity decides the sheets; sizing applies only to single images
        sizingLabel.setText(on ? "Single images:" : "Image sizing:");
        copiesLabel.setVisible(!on);
        copies.setVisible(!on);
        if (on && method() != SendMethod.DRIVER && !cfg.imposeDefaultPaperId.isBlank()) selectPaper(cfg.imposeDefaultPaperId);
        cfg.lastImpose = on;
        refresh();
    }

    private void updateGridHint() {
        boolean fit = modeBox.getSelectedItem() == ImposeSettings.Mode.FIT_GRID;
        gridHint.setText(fit ? "0 = as many as fit · pieces resize to fill the paper" : "(0 = automatic)");
        gridHint.setForeground(fit ? new Color(0, 90, 160) : Color.GRAY);
    }

    private boolean isCustom() {
        SizeChoice c = (SizeChoice) sizeBox.getSelectedItem();
        return c != null && c.kind().equals("CUSTOM");
    }

    private AutoSize.Guess guess(Entry e) {
        if (e.guess == null && e.px != null) {
            e.guess = AutoSize.guess(e.file, e.px, Preset.parse(cfg.imposePresets));
        }
        return e.guess;
    }

    /** Piece size for this entry: its own automatic guess, or the chosen preset / custom size. */
    private Preset pieceFor(Entry e) {
        SizeChoice c = (SizeChoice) sizeBox.getSelectedItem();
        if (c == null || c.kind().equals("AUTO")) {
            AutoSize.Guess g = guess(e);
            return g == null ? null : g.size();
        }
        if (c.kind().equals("CUSTOM")) {
            return new Preset("Custom", ((Number) customW.getValue()).doubleValue(), ((Number) customH.getValue()).doubleValue());
        }
        return c.preset();
    }

    private ImposeSettings imposeFor(Entry e) {
        Preset piece = pieceFor(e);
        if (piece == null) return null;
        return new ImposeSettings(piece, num(bleedMm), num(gapMm), num(marginMm), cfg.imposeAllowRotation,
                (Integer) cols.getValue(), (Integer) rows.getValue(), cfg.imposeFill, (Integer) quantity.getValue(),
                cfg.imposeBackOffsetXmm, cfg.imposeBackOffsetYmm, cfg.imposeInfoLine,
                (ImposeSettings.Mode) modeBox.getSelectedItem());
    }

    /** Sheet size in points and its name, from the chosen paper; null if unknown. */
    private Object[] sheet() {
        if (method() == SendMethod.DRIVER) {
            DriverPaper dp = (DriverPaper) driverPaperBox.getSelectedItem();
            if (dp == null || dp.media() == null) return null;
            double[] in = DriverPrinter.inches(dp.media()).orElse(null);
            return in == null ? null : turned(new Object[]{in[0] * 72, in[1] * 72, dp.media().toString()});
        }
        Paper p = (Paper) paperBox.getSelectedItem();
        if (p == null || !p.hasSize()) return null;
        return turned(new Object[]{p.widthPt(), p.heightPt(), p.name()});
    }

    /** When imposing, the sheet is turned to the chosen Portrait / Landscape, whatever the file's shape. */
    private Object[] turned(Object[] sh) {
        if (!imposeBox.isSelected()) return sh;
        Orientation o = (Orientation) orientationBox.getSelectedItem();
        double[] wh = (o == null ? Orientation.PORTRAIT : o).apply((double) sh[0], (double) sh[1]);
        String name = sh[2] + (o == Orientation.LANDSCAPE ? " (landscape)" : " (portrait)");
        return new Object[]{wh[0], wh[1], name};
    }

    /** True if this entry becomes a sheet PDF (imposed and/or front + back). */
    private boolean needsSheet(Entry e) {
        return e.isImage() && (imposeBox.isSelected() || e.back != null);
    }

    private SheetJob sheetJob(Entry e) {
        Object[] sh = sheet();
        ImposeSettings imp = imposeBox.isSelected() ? imposeFor(e) : null;
        return new SheetJob(e.file, e.back, imp,
                sh == null ? 0 : (double) sh[0], sh == null ? 0 : (double) sh[1], sh == null ? "?" : (String) sh[2],
                sizing(), (Integer) copies.getValue(), allowLowRes.isSelected());
    }

    private SheetMaker.Plan planFor(Entry e) {
        return SheetMaker.plan(sheetJob(e), e.px);
    }

    // ================================================================== actions

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

    private void selectPaper(String id) {
        if (id == null) return;
        for (int i = 0; i < paperBox.getItemCount(); i++) {
            if (paperBox.getItemAt(i).id().equals(id)) { paperBox.setSelectedIndex(i); return; }
        }
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

        if (hub.isCustomerRequired() && customerPicker.selected() == null) {
            JOptionPane.showMessageDialog(this, "Choose the customer first.\n\nType part of the name or phone to find them, "
                    + "or click '+ New' to add a new customer.", "Customer missing", JOptionPane.WARNING_MESSAGE);
            customerPicker.requestFocusInWindow();
            return;
        }
        CustomerRef customer = chosenCustomer();

        List<String> problems = new ArrayList<>();
        List<String> tooLow = new ArrayList<>();
        if (imposeBox.isSelected()) {
            if ((Integer) quantity.getValue() <= 0) problems.add("Enter the quantity (pieces) to impose.");
            if (isCustom() && (num(customW) <= 0 || num(customH) <= 0)) problems.add("Enter the piece width and height (mm).");
            if (!problems.isEmpty()) {
                JOptionPane.showMessageDialog(this, String.join("\n", problems), "Can't send yet", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        for (Entry e : ticked) {
            if (imposeBox.isSelected() && !e.isImage()) {
                problems.add(e.file.getFileName() + ": imposition works with image files (JPG, PNG, TIFF…)");
                continue;
            }
            if (needsSheet(e)) {
                SheetMaker.Plan p = planFor(e);
                if (!p.ok()) { problems.add(e.file.getFileName() + ": " + p.problem()); continue; }
                if (p.dpi() < cfg.minDpi) tooLow.add(e.file.getFileName() + " (" + p.dpi() + " DPI)");
            } else {
                Integer dpi = dpiOf(e);
                if (dpi != null && dpi < cfg.minDpi) tooLow.add(e.file.getFileName() + " (" + dpi + " DPI)");
            }
        }
        if (!problems.isEmpty()) {
            JOptionPane.showMessageDialog(this, String.join("\n", problems), "Can't send yet", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!tooLow.isEmpty() && !allowLowRes.isSelected()) {
            JOptionPane.showMessageDialog(this, "These files are too low resolution for this size:\n\n"
                            + String.join("\n", tooLow)
                            + "\n\nUntick them, choose a smaller size, or tick 'Allow low resolution'.",
                    "Low resolution", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Sizing sizing = sizing();
        int n = (Integer) copies.getValue();
        List<Path> allFiles = new ArrayList<>();
        ticked.forEach(e -> allFiles.addAll(e.files()));
        cfg.lastSizing = sizing;
        cfg.sendMethod = method();
        cfg.lastImpose = imposeBox.isSelected();

        if (method() == SendMethod.DRIVER) {
            String printer = (String) printerBox.getSelectedItem();
            DriverPaper paper = (DriverPaper) driverPaperBox.getSelectedItem();
            MediaSizeName media = paper == null ? null : paper.media();
            cfg.driverPrinterName = printer;
            cfg.driverPaper = media == null ? "" : media.toString();
            DriverPrintOptions o = new DriverPrintOptions(printer, media, sizing, n,
                    combineBox.isSelected(), dialogBox.isSelected());
            Map<Path, SheetJob> sheets = new LinkedHashMap<>();
            List<Path> fronts = new ArrayList<>();
            for (Entry e : ticked) {
                fronts.add(e.file);
                if (needsSheet(e)) sheets.put(e.file, sheetJob(e));
            }
            resetForm();
            removeFiles(allFiles);
            onDriverPrint.accept(new DriverBatch(fronts, o, sheets, customer));
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
            requests.add(new JobRequest(e.file, name, paper, sizing, n, instructions.getText().trim(),
                    allowLowRes.isSelected(), converter, needsSheet(e) ? sheetJob(e) : null, customer));
        }
        if (!imposeBox.isSelected()) cfg.lastPaperId = paper.id();
        resetForm();
        removeFiles(allFiles);
        onSend.accept(requests);
    }

    private void resetForm() {
        allowLowRes.setSelected(false);
        instructions.setText("");
        copies.setValue(1);
        customerPicker.clear();
        cols.setValue(0);
        rows.setValue(0);
        quantity.setValue(0);
        customW.setValue(0.0);
        customH.setValue(0.0);
        marginMm.setValue(cfg.imposeMarginMm);
        gapMm.setValue(cfg.imposeGapMm);
        bleedMm.setValue(cfg.imposeBleedMm);
    }

    private void skipTicked() {
        List<Path> files = new ArrayList<>();
        ticked().forEach(e -> files.addAll(e.files()));
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
            if (e.back != null) Desktop.getDesktop().open(e.back.toFile());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Cannot open the file: " + ex.getMessage());
        }
    }

    private void onRowSelected() {
        Entry e = selected();
        if (e != null && ticked().size() <= 1) jobName.setText(DocumentNormalizer.safeName(e.file.getFileName().toString()));
        refresh();
    }

    /** Re-evaluates everything that depends on the current choices. */
    private void refresh() {
        if (updating) return;
        model.fireTableRowsUpdated(0, Math.max(0, entries.size() - 1));
        updateButtons();
        updateImposeInfo();
        showPreview(selected());
    }

    private void updateButtons() {
        List<Entry> t = ticked();
        boolean driver = method() == SendMethod.DRIVER;
        String what = t.size() <= 1 ? "" : t.size() + " jobs ";
        String verb = imposeBox.isSelected() ? "Impose & " : "";
        if (driver) {
            sendButton.setText(combineBox.isSelected() && t.size() > 1 && !imposeBox.isSelected()
                    ? "Print " + t.size() + " files as one job (driver)"
                    : verb + "Print " + what + "(driver, like Ctrl+P)");
            sendButton.setEnabled(!t.isEmpty() && printerBox.getSelectedItem() != null);
        } else if (method() == SendMethod.DRIVER_API) {
            sendButton.setText(verb + "Convert " + what + "to PostScript & send (Hold)");
            sendButton.setEnabled(!t.isEmpty() && paperBox.getSelectedItem() != null);
        } else {
            sendButton.setText(verb + "Send " + what + "to Fiery (Hold)");
            sendButton.setEnabled(!t.isEmpty() && paperBox.getSelectedItem() != null);
        }
        skipButton.setEnabled(!t.isEmpty());
        long images = t.stream().filter(e -> e.back == null && e.isImage()).count();
        pairButton.setEnabled(t.size() == 2 && images == 2);
        Entry sel = selected();
        swapButton.setEnabled(sel != null && sel.back != null);
        unpairButton.setEnabled(sel != null && sel.back != null);
        jobName.setEnabled(t.size() <= 1);
        if (t.size() > 1) jobName.setText("(each file uses its own name)");
    }

    private void updatePaperDetails() {
        Paper p = (Paper) paperBox.getSelectedItem();
        paperDetails.setText(p == null ? "No paper catalog loaded" : p.details());
    }

    private void updateImposeInfo() {
        if (!imposeBox.isSelected()) return;
        Entry e = selected();
        if (e == null) { guessLabel.setText(" "); summary.setText(" "); return; }
        if (!e.isImage()) {
            guessLabel.setText("Not an image – imposition works with JPG, PNG, TIFF…");
            summary.setText(" ");
            return;
        }
        SizeChoice c = (SizeChoice) sizeBox.getSelectedItem();
        AutoSize.Guess g = guess(e);
        if (c != null && c.kind().equals("AUTO") && g != null) {
            guessLabel.setText("<html>This file: <b>" + g.size().name() + " " + g.size().sizeLabel() + "</b> – " + g.reason() + "</html>");
        } else {
            Preset p = pieceFor(e);
            guessLabel.setText(p == null ? " " : "Piece: " + p.sizeLabel());
        }
        if (isCustom() && (num(customW) <= 0 || num(customH) <= 0)) {
            summary.setForeground(Color.RED);
            summary.setText("Enter the piece width and height");
            return;
        }
        SheetMaker.Plan plan = planFor(e);
        summary.setForeground(plan.ok() ? (plan.dpi() < cfg.minDpi ? Color.RED : GREEN) : Color.RED);
        String text = plan.ok() ? plan.summary() + " · " + plan.dpi() + " DPI" : plan.summary();
        summary.setText("<html><div style='width:300px'>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>");
    }

    // ================================================================== preview

    private void showPreview(Entry e) {
        int token = ++previewToken;
        if (e == null) { preview.setIcon(null); preview.setText(""); fileInfo.setText(" "); return; }
        StringBuilder info = new StringBuilder("<html>").append(e.back != null ? "Front + Back · " : "").append(e.kind.label());
        if (e.px != null) info.append(", ").append(e.px.width).append(" × ").append(e.px.height).append(" px");
        info.append(", ").append(humanSize(e.size)).append("</html>");
        fileInfo.setText(info.toString());
        if (sizing() == Sizing.ACTUAL && !needsSheet(e)) showFilePageSize(e, info.toString(), token);

        boolean sheetView = needsSheet(e);
        SheetMaker.Plan plan = sheetView ? planFor(e) : null;
        new SwingWorker<BufferedImage, Void>() {
            @Override protected BufferedImage doInBackground() {
                BufferedImage front = thumb(e.file, e.kind);
                if (!sheetView || plan == null || plan.layout() == null || !plan.layout().fits()) return front;
                SheetLayout l = plan.layout();
                boolean marks = imposeBox.isSelected();
                int w = e.back == null ? 460 : 228;
                BufferedImage f = SheetPreview.render(l, l.front, front, plan.fill(), marks, w, 320, e.back == null ? null : "Front");
                if (e.back == null) return f;
                BufferedImage backThumb = thumb(e.back, detectKind(e.back));
                BufferedImage b = SheetPreview.render(l,
                        l.back(cfg.imposeBackOffsetXmm * ImposeSettings.PT_PER_MM, cfg.imposeBackOffsetYmm * ImposeSettings.PT_PER_MM),
                        backThumb, plan.fill(), false, w, 320, "Back");
                BufferedImage both = new BufferedImage(f.getWidth() + b.getWidth() + 6, Math.max(f.getHeight(), b.getHeight()), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = both.createGraphics();
                g.setColor(new Color(0xEE, 0xEF, 0xF2));
                g.fillRect(0, 0, both.getWidth(), both.getHeight());
                g.drawImage(f, 0, 0, null);
                g.drawImage(b, f.getWidth() + 6, 0, null);
                g.dispose();
                return both;
            }
            @Override protected void done() {
                if (token != previewToken) return; // another change happened meanwhile
                try {
                    BufferedImage img = get();
                    if (img == null) {
                        preview.setIcon(null);
                        preview.setText(e.kind.label() + " – no preview, double-click to open");
                        return;
                    }
                    double s = Math.min(460.0 / img.getWidth(), 295.0 / img.getHeight());
                    Image shown = s < 1 ? img.getScaledInstance((int) (img.getWidth() * s), (int) (img.getHeight() * s), Image.SCALE_SMOOTH) : img;
                    preview.setIcon(new ImageIcon(shown));
                    preview.setText(null);
                } catch (Exception ex) {
                    preview.setIcon(null);
                    preview.setText("No preview");
                }
            }
        }.execute();
    }

    private BufferedImage thumb(Path file, FileKind kind) {
        return thumbs.computeIfAbsent(file, f -> DocumentNormalizer.thumbnail(f, kind, 600).orElse(null));
    }

    // ================================================================== quality

    /** Where this file's image lands: on the chosen paper, or at its actual size (its own stored DPI). */
    private PageSpec currentSpec(Entry e) {
        if (sizing() == Sizing.ACTUAL) return Sizing.ACTUAL.pageSpec(null, e.file);
        if (method() == SendMethod.DRIVER) {
            DriverPaper dp = (DriverPaper) driverPaperBox.getSelectedItem();
            double[] inches = dp == null ? null : DriverPrinter.inches(dp.media()).orElse(null);
            return sizing().pageSpec(inches);
        }
        return sizing().pageSpec((Paper) paperBox.getSelectedItem());
    }

    private boolean isPlaced(Entry e) {
        if (e.px == null) return false;
        if (method() != SendMethod.API) return true;
        return !e.kind.isPrintReady() && cfg.imageMode == AppConfig.ImageMode.CONVERT_TO_PDF;
    }

    /**
     * "Actual size": adds the page size that will be sent to the Fiery to the file line, e.g.
     * "page 3.5 × 2 in (89 × 51 mm)". Measured in the background (a big PDF takes a moment).
     */
    private void showFilePageSize(Entry e, String fileLine, int token) {
        new SwingWorker<Optional<FilePageSize>, Void>() {
            @Override protected Optional<FilePageSize> doInBackground() { return FilePageSize.of(e.file); }

            @Override protected void done() {
                if (token != previewToken) return;          // another file was selected meanwhile
                try {
                    String page = get().map(size -> "page " + size.label() + " – sent to the Fiery as the page size")
                            .orElse("page size unknown – the Fiery uses the paper's size");
                    fileInfo.setText(fileLine.replace("</html>", "<br>" + page + "</html>"));
                } catch (Exception ignored) {
                    // leave the file line as it is
                }
            }
        }.execute();
    }

    private Integer dpiOf(Entry e) {
        if (needsSheet(e)) {
            SheetMaker.Plan p = planFor(e);
            return p.ok() ? p.dpi() : null;
        }
        if (!isPlaced(e)) return null;
        return currentSpec(e).layout(e.px.width, e.px.height).dpi();
    }

    private String qualityText(Entry e) {
        if (needsSheet(e)) {
            SheetMaker.Plan p = planFor(e);
            if (!p.ok()) return p.summary();
            return p.dpi() + " DPI · " + (imposeBox.isSelected() ? p.layout().perSheet() + "-up · " + p.copies() + " sheets" : "front + back");
        }
        if (imposeBox.isSelected() && !e.isImage()) return "Not an image – can't impose";
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
        PageSpec.Layout l = currentSpec(e).layout(e.px.width, e.px.height);
        StringBuilder sb = new StringBuilder().append(l.dpi()).append(" DPI");
        if (l.rotate()) sb.append(", rotated");
        if (l.cropped()) sb.append(", cropped");
        if (l.margins()) sb.append(", margins");
        return sb.toString();
    }

    private Color qualityColor(Entry e) {
        if (needsSheet(e)) {
            SheetMaker.Plan p = planFor(e);
            if (!p.ok()) return Color.RED;
            return p.dpi() < cfg.minDpi ? Color.RED : p.dpi() < cfg.warnDpi ? AMBER : GREEN;
        }
        if (imposeBox.isSelected() && !e.isImage()) return Color.RED;
        if (method() == SendMethod.DRIVER && e.kind == FileKind.POSTSCRIPT) return Color.RED;
        if (e.px == null) return (e.kind == FileKind.PDF || e.kind.isPrintReady()) ? GREEN : Color.RED;
        Integer dpi = dpiOf(e);
        if (dpi == null) return e.kind.isPrintReady() ? GREEN : AMBER;
        return dpi < cfg.minDpi ? Color.RED : dpi < cfg.warnDpi ? AMBER : GREEN;
    }

    // ================================================================== helpers

    private List<Entry> ticked() { return entries.stream().filter(x -> x.checked).toList(); }

    private Entry selected() {
        int r = table.getSelectedRow();
        return r < 0 || r >= entries.size() ? null : entries.get(table.convertRowIndexToModel(r));
    }

    private static double num(JSpinner s) { return ((Number) s.getValue()).doubleValue(); }

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
        private final String[] colNames = {"Send", "File", "Type", "Size", "Quality / sheets"};
        @Override public int getRowCount() { return entries.size(); }
        @Override public int getColumnCount() { return colNames.length; }
        @Override public String getColumnName(int c) { return colNames[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }
        @Override public Object getValueAt(int r, int c) {
            Entry e = entries.get(r);
            return switch (c) {
                case 0 -> e.checked;
                case 1 -> e.back == null ? e.file.getFileName().toString()
                        : "F: " + e.file.getFileName() + "   ⇄   B: " + e.back.getFileName();
                case 2 -> e.back != null ? "Front + Back" : e.kind.label();
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
