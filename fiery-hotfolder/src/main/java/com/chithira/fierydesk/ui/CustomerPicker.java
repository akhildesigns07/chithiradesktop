package com.chithira.fierydesk.ui;

import com.chithira.fierydesk.hub.HubConnection;
import com.chithira.fierydesk.hub.HubCustomer;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.List;
import java.util.Objects;

/**
 * "Customer:" row of the Files waiting window: type part of the name, phone or code to filter the list
 * (from the hub, cached on this PC), pick the customer, or click "+ New" to add one to the hub.
 */
class CustomerPicker extends JPanel {

    private static final int MAX_RESULTS = 200;

    private final HubConnection hub;
    private final JTextField searchField = new JTextField(12);
    private final JComboBox<HubCustomer> resultBox = new JComboBox<>();
    private final JButton newButton = new JButton("+ New");
    private Runnable onChange = () -> {};

    CustomerPicker(HubConnection hub) {
        super(new BorderLayout(4, 0));
        this.hub = hub;
        searchField.setToolTipText("Search name, phone, company or code (e.g. C-0042)");
        resultBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value == null) {
                    setText(hub.isCustomerRequired() ? "— choose the customer —" : "— no customer —");
                    setForeground(Color.GRAY);
                }
                return this;
            }
        });
        newButton.setToolTipText("Add a new customer to the Print Hub");

        add(searchField, BorderLayout.WEST);
        add(resultBox, BorderLayout.CENTER);
        add(newButton, BorderLayout.EAST);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshResults(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshResults(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshResults(); }
        });
        resultBox.addActionListener(e -> onChange.run());
        newButton.addActionListener(e -> addNewCustomer());
        hub.addCustomerListener(() -> SwingUtilities.invokeLater(this::refreshResults));
        refreshResults();
    }

    /** The chosen customer, or null. */
    HubCustomer selected() { return (HubCustomer) resultBox.getSelectedItem(); }

    /** Back to "no customer" for the next batch, so a job is never booked to the previous customer. */
    void clear() {
        searchField.setText("");
        resultBox.setSelectedItem(null);
    }

    void setOnChange(Runnable onChange) { this.onChange = onChange; }

    /** Refills the list from the search text, keeping the current choice if it still matches. */
    private void refreshResults() {
        HubCustomer previous = selected();
        List<HubCustomer> matches = hub.customers().search(searchField.getText(), MAX_RESULTS);
        DefaultComboBoxModel<HubCustomer> model = new DefaultComboBoxModel<>();
        model.addElement(null);
        matches.forEach(model::addElement);
        resultBox.setModel(model);
        HubCustomer keep = previous == null ? null
                : matches.stream().filter(customer -> customer.id() == previous.id()).findFirst().orElse(null);
        boolean searching = !searchField.getText().isBlank();
        resultBox.setSelectedItem(keep != null ? keep : searching && matches.size() == 1 ? matches.get(0) : null);
        if (searching && keep == null && matches.size() > 1 && searchField.hasFocus()) resultBox.showPopup();
        onChange.run();
    }

    private void addNewCustomer() {
        String typed = searchField.getText().trim();
        boolean looksLikePhone = typed.matches("[+0-9 ()-]{6,}");
        NewCustomerDialog dialog = new NewCustomerDialog(SwingUtilities.getWindowAncestor(this), hub,
                looksLikePhone ? "" : typed, looksLikePhone ? typed : "");
        HubCustomer created = dialog.showAndWait();
        if (created != null) select(created);
    }

    private void select(HubCustomer customer) {
        searchField.setText("");          // shows every customer again …
        DefaultComboBoxModel<HubCustomer> model = (DefaultComboBoxModel<HubCustomer>) resultBox.getModel();
        for (int i = 0; i < model.getSize(); i++) {
            if (model.getElementAt(i) != null && Objects.equals(model.getElementAt(i).id(), customer.id())) {
                resultBox.setSelectedIndex(i);          // … with the new one chosen
                return;
            }
        }
        model.addElement(customer);
        resultBox.setSelectedItem(customer);
    }
}
