package com.chithira.mockfiery;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Settings from mock-fiery.properties (next to "Mock Fiery.exe" / the jar, or the
 * working folder). A commented template is created on first start.
 */
final class MockConfig {

    static final String FILE_NAME = "mock-fiery.properties";

    Path file;
    int httpsPort = 8443;
    boolean https = true;
    int rawPort = 9100;
    String apiKey = "MOCK-KEY";
    String username = "test";
    String password = "test";
    int sessionMinutes = 30;
    Path dataDir;
    String duplexAttribute = "duplex";
    String keystore = "";
    String keystorePassword = "";

    static MockConfig load() throws IOException {
        MockConfig c = new MockConfig();
        Path home = homeDir();
        c.file = home.resolve(FILE_NAME);
        if (!Files.exists(c.file)) writeTemplate(c.file);
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(c.file)) {
            p.load(in);
        }
        c.httpsPort = Integer.parseInt(p.getProperty("port", "8443").trim());
        c.https = Boolean.parseBoolean(p.getProperty("https", "true").trim());
        c.rawPort = Integer.parseInt(p.getProperty("raw-print-port", "9100").trim());
        c.apiKey = p.getProperty("api-key", c.apiKey).trim();
        c.username = p.getProperty("username", c.username).trim();
        c.password = p.getProperty("password", c.password).trim();
        c.sessionMinutes = Integer.parseInt(p.getProperty("session-minutes", "30").trim());
        String data = p.getProperty("data-folder", "").trim();
        c.dataDir = data.isEmpty() ? home.resolve("mock-fiery-data") : Path.of(data);
        c.duplexAttribute = p.getProperty("duplex-attribute", c.duplexAttribute).trim();
        c.keystore = p.getProperty("keystore", "").trim();
        c.keystorePassword = p.getProperty("keystore-password", "").trim();
        return c;
    }

    /** Folder of the exe (jpackage), else of the jar, else the working folder. */
    static Path homeDir() {
        String exe = System.getProperty("jpackage.app-path");
        if (exe != null && !exe.isBlank()) return Path.of(exe).toAbsolutePath().getParent();
        try {
            Path p = Path.of(MockFiery.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(p)) return p.getParent();
        } catch (URISyntaxException | RuntimeException ignored) {
            // fall through
        }
        return Path.of("").toAbsolutePath();
    }

    private static void writeTemplate(Path f) throws IOException {
        Files.writeString(f, """
                # ================================================================
                #  Mock Fiery Server settings. Restart the mock after changing.
                # ================================================================

                # API port. The hot folder app uses  fiery.base-url=https://localhost:8443
                port=8443
                # true = HTTPS with a self-signed certificate (like a real Fiery); false = plain http
                https=true

                # Raw printing port (Windows printer -> Standard TCP/IP port -> RAW, port 9100)
                raw-print-port=9100

                # Login the app must use
                api-key=MOCK-KEY
                username=test
                password=test

                # Sessions expire after this many minutes (tests automatic re-login)
                session-minutes=30

                # Where received jobs are saved (blank = mock-fiery-data next to the exe)
                data-folder=

                # Job attribute that means "two-sided" (shown in the Duplex column)
                duplex-attribute=duplex

                # Optional: your own certificate (PKCS12). Blank = built-in localhost test certificate
                keystore=
                keystore-password=
                """, StandardCharsets.UTF_8);
    }
}
