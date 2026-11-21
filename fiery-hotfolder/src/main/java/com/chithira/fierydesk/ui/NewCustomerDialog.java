package com.chithira.fierydesk.ui;

import com.chithira.fierydesk.hub.HubClient;
import com.chithira.fierydesk.hub.HubConnection;
import com.chithira.fierydesk.hub.HubCustomer;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.ExecutionException;

/**
 * Quick add of a customer from the desktop. The customer is created in the Print Hub straight away
 * (and appears in every desktop's list). If the phone number already belongs to a customer, that customer is used.
 */
class NewCustomerDialog extends JDialog {

    private final HubConnection hub;
    private final JTextField nameField = new JTextField(24);
    private final JTextField phoneField = new JTextField(24);
    private final JTextField emailField = new JTextField(24);
    private final JTextField companyField = new JTextField(24);
    private final JLabel messageLabel = new JLabel(" ");
    private final JButton saveButton = new JButton("Add customer");
    private HubCustomer result;

    NewCustomerDialog(Window owner, HubConnection hub, String name, String phone) {
        super(owner, "New customer", ModalityType.APPLICATION_MODAL);
        this.hub = hub;
        nameField.setText(name);
        phoneField.setText(phone);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.anchor = GridBagConstraints.WEST;
        int row = 0;
        row = addRow(form, constraints, row, "Name *", nameField);
        row = addRow(form, constraints, row, "Phone", phoneField);
        row = addRow(form, constraints, row, "Email", emailField);
        row = addRow(form, constraints, row, "Company", companyField);
        constraints.gridx = 0; constraints.gridy = row; constraints.gridwidth = 2;
        messageLabel.setForeground(new Color(170, 0, 0));
        form.add(messageLabel, constraints);

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dispose());
        saveButton.addActionListener(e -> save());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancelButton);
        buttons.add(saveButton);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(saveButton);
        pack();
        setLocationRelativeTo(owner);
    }

    /** Shows the dialog; returns the customer created (or found by phone), or null if cancelled. */
    HubCustomer showAndWait() {
        setVisible(true);
        return result;
    }

    private void save() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            messageLabel.setText("Enter the customer's name");
            nameField.requestFocus();
            return;
        }
        saveButton.setEnabled(false);
        messageLabel.setForeground(Color.DARK_GRAY);
        messageLabel.setText("Saving to the Print Hub …");
        new SwingWorker<HubClient.CreatedCustomer, Void>() {
            @Override
            protected HubClient.CreatedCustomer doInBackground() throws Exception {
                return hub.createCustomer(name, blankToNull(phoneField.getText()), blankToNull(emailField.getText()),
                        blankToNull(companyField.getText()));
            }

            @Override
            protected void done() {
                try {
                    HubClient.CreatedCustomer created = get();
                    if (created.alreadyExisted()) {
                        JOptionPane.showMessageDialog(NewCustomerDialog.this,
                                "This phone number already belongs to\n" + created.customer() + "\n\nThat customer is chosen.",
                                "Customer already exists", JOptionPane.INFORMATION_MESSAGE);
                    }
                    result = created.customer();
                    dispose();
                } catch (ExecutionException e) {
                    showError(e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private void showError(String message) {
        saveButton.setEnabled(true);
        messageLabel.setForeground(new Color(170, 0, 0));
        messageLabel.setText("<html><div style='width:280px'>" + escape(message) + "</div></html>");
        pack();
    }

    private static int addRow(JPanel form, GridBagConstraints constraints, int row, String label, JComponent field) {
        constraints.gridwidth = 1;
        constraints.gridx = 0; constraints.gridy = row;
        form.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        form.add(field, constraints);
        return row + 1;
    }

    private static String blankToNull(String text) { return text == null || text.isBlank() ? null : text.trim(); }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
