package com.chithira.mockfiery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a multipart/form-data body (what Postman's form-data tab and the app send). */
final class Multipart {

    record Part(String name, String filename, String contentType, byte[] data) {
        String text() { return new String(data, StandardCharsets.UTF_8); }
    }

    private static final Pattern BOUNDARY = Pattern.compile("boundary=(?:\"([^\"]+)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile("[; ]name=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME = Pattern.compile("filename=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    private Multipart() {}

    static String boundary(String contentType) {
        if (contentType == null) return null;
        Matcher m = BOUNDARY.matcher(contentType);
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
    }

    static List<Part> parse(byte[] body, String boundary) {
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        List<Part> parts = new ArrayList<>();
        int pos = indexOf(body, delim, 0);
        while (pos >= 0) {
            int start = pos + delim.length;
            if (start + 1 < body.length && body[start] == '-' && body[start + 1] == '-') break; // closing --boundary--
            start = skipCrlf(body, start);
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), start);
            if (headerEnd < 0) break;
            String headers = new String(body, start, headerEnd - start, StandardCharsets.UTF_8);
            int dataStart = headerEnd + 4;
            int next = indexOf(body, delim, dataStart);
            if (next < 0) break;
            int dataEnd = next - 2; // CRLF before the next boundary
            if (dataEnd < dataStart) dataEnd = dataStart;
            byte[] data = new byte[dataEnd - dataStart];
            System.arraycopy(body, dataStart, data, 0, data.length);

            String name = null, filename = null, type = null;
            for (String line : headers.split("\r\n")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-disposition:")) {
                    Matcher n = NAME.matcher(line);
                    if (n.find()) name = n.group(1);
                    Matcher f = FILENAME.matcher(line);
                    if (f.find()) filename = f.group(1);
                } else if (lower.startsWith("content-type:")) {
                    type = line.substring(line.indexOf(':') + 1).trim();
                }
            }
            parts.add(new Part(name, filename, type, data));
            pos = next;
        }
        return parts;
    }

    private static int skipCrlf(byte[] b, int i) {
        if (i + 1 < b.length && b[i] == '\r' && b[i + 1] == '\n') return i + 2;
        return i;
    }

    static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
