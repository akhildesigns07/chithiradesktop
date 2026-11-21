package com.chithira.fierydesk.fiery;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Builds a multipart/form-data body, the same thing Postman's form-data tab sends. */
final class Multipart {
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private final String boundary = "----ChithiraFiery" + UUID.randomUUID().toString().replace("-", "");
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Multipart text(String name, String value) {
        if (value == null) return this;
        header("Content-Disposition: form-data; name=\"" + name + "\"");
        write(CRLF);
        write(value.getBytes(StandardCharsets.UTF_8));
        write(CRLF);
        return this;
    }

    Multipart file(String name, String filename, String contentType, byte[] content) {
        header("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + safe(filename) + "\"");
        write(("Content-Type: " + contentType).getBytes(StandardCharsets.US_ASCII));
        write(CRLF);
        write(CRLF);
        write(content);
        write(CRLF);
        return this;
    }

    /** Value for the Content-Type header, including the boundary. */
    String contentType() { return "multipart/form-data; boundary=" + boundary; }

    byte[] build() {
        write(("--" + boundary + "--").getBytes(StandardCharsets.US_ASCII));
        write(CRLF);
        return out.toByteArray();
    }

    private void header(String disposition) {
        write(("--" + boundary).getBytes(StandardCharsets.US_ASCII));
        write(CRLF);
        write(disposition.getBytes(StandardCharsets.UTF_8));
        write(CRLF);
    }

    private void write(byte[] b) { out.write(b, 0, b.length); }

    private static String safe(String filename) {
        return filename.replace("\"", "_").replace("\r", "").replace("\n", "");
    }
}
