package com.chithira.mockfiery;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.Executors;

/**
 * Mock Fiery Server: pretends to be a Xerox Fiery so the hot folder app can be tested
 * without touching the real press.
 *
 *   https://localhost:8443/live/api/v5/...   Fiery API (login, papercatalog, jobs)
 *   https://localhost:8443/                  Hold-queue web page with previews and test switches
 *   localhost:9100                           raw printing (Windows printer, PostScript driver)
 */
public final class MockFiery {

    public static void main(String[] args) throws Exception {
        MockConfig cfg = MockConfig.load();
        MockState state = new MockState(cfg);

        HttpServer server;
        try {
            if (cfg.https) {
                HttpsServer https = HttpsServer.create(new InetSocketAddress(cfg.httpsPort), 0);
                https.setHttpsConfigurator(new HttpsConfigurator(sslContext(cfg)));
                server = https;
            } else {
                server = HttpServer.create(new InetSocketAddress(cfg.httpsPort), 0);
            }
        } catch (BindException e) {
            fail("Port " + cfg.httpsPort + " is already in use. Is the mock already running? Change 'port' in " + cfg.file);
            return;
        }
        server.createContext(FieryApi.BASE, new FieryApi(state));
        server.createContext("/", new WebPage(state));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        String raw;
        try {
            new RawPrintServer(state).start();
            raw = "localhost:" + cfg.rawPort + " (RAW)";
        } catch (BindException e) {
            raw = "NOT started – port " + cfg.rawPort + " is in use";
        }

        String web = (cfg.https ? "https" : "http") + "://localhost:" + cfg.httpsPort + "/";
        System.out.println("""
                ==========================================================
                 Mock Fiery Server is running
                   Hold queue web page : %s
                   Fiery API           : %slive/api/v5
                   Raw printing        : %s
                   Login               : api key %s, user %s
                   Settings file       : %s
                   Received jobs       : %s
                 In the hot folder app set  fiery.base-url=%s
                 Close this window to stop the mock.
                ==========================================================
                """.formatted(web, web, raw, cfg.apiKey, cfg.username, cfg.file, cfg.dataDir.resolve("jobs"),
                web.substring(0, web.length() - 1)));

        if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported() && !Boolean.getBoolean("mockfiery.noBrowser")) {
            try { Desktop.getDesktop().browse(new URI(web)); } catch (Exception ignored) { /* open it by hand */ }
        }
    }

    private static SSLContext sslContext(MockConfig cfg) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        char[] pass;
        if (!cfg.keystore.isEmpty()) {
            pass = cfg.keystorePassword.toCharArray();
            try (InputStream in = Files.newInputStream(Path.of(cfg.keystore))) { ks.load(in, pass); }
        } else {
            pass = "mockfiery".toCharArray();   // built-in localhost test certificate (not secret)
            try (InputStream in = MockFiery.class.getResourceAsStream("/mockfiery/mock-fiery.p12")) { ks.load(in, pass); }
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private static void fail(String msg) {
        System.err.println(msg);
        if (!GraphicsEnvironment.isHeadless()) {
            javax.swing.JOptionPane.showMessageDialog(null, msg, "Mock Fiery", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
        System.exit(1);
    }
}
