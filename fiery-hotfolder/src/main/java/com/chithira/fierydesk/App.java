package com.chithira.fierydesk;

import com.chithira.fierydesk.core.AppConfig;
import com.chithira.fierydesk.ui.MainWindow;

import javax.swing.*;

public final class App {
    public static void main(String[] args) {
        AppConfig cfg = AppConfig.load();

        // The Fiery's self-signed certificate is usually issued to its hostname, not its IP.
        // The JDK HTTP client reads this flag once, so it must be set before any request.
        if (cfg.fiery.trustSelfSigned()) {
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // default look and feel is fine
        }
        SwingUtilities.invokeLater(() -> new MainWindow(cfg).show());
    }
}
