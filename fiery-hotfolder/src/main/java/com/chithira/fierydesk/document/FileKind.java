package com.chithira.fierydesk.document;

/** What a file really is, detected from its first bytes (not its extension). */
public enum FileKind {
    PDF("PDF document"), POSTSCRIPT("PostScript"), TIFF("TIFF image"),
    JPEG("JPEG image"), PNG("PNG image"), OTHER_IMAGE("Image"), UNKNOWN("Unknown");

    private final String label;
    FileKind(String label) { this.label = label; }
    public String label() { return label; }

    public String mimeType() {
        return switch (this) {
            case PDF -> "application/pdf";
            case POSTSCRIPT -> "application/postscript";
            case TIFF -> "image/tiff";
            case JPEG -> "image/jpeg";
            case PNG -> "image/png";
            default -> "application/octet-stream";
        };
    }

    /** Already a print language the Fiery accepts. */
    public boolean isPrintReady() { return this == PDF || this == POSTSCRIPT || this == TIFF; }

    public static FileKind detect(byte[] b) {
        if (b == null || b.length < 4) return UNKNOWN;
        if (starts(b, "%PDF")) return PDF;
        if (starts(b, "%!PS")) return POSTSCRIPT;
        if ((b[0] & 0xFF) == 0xC5 && (b[1] & 0xFF) == 0xD0 && (b[2] & 0xFF) == 0xD3 && (b[3] & 0xFF) == 0xC6)
            return POSTSCRIPT; // EPS with binary header
        if ((b[0] == 'I' && b[1] == 'I' && b[2] == 42 && b[3] == 0)
                || (b[0] == 'M' && b[1] == 'M' && b[2] == 0 && b[3] == 42)) return TIFF;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return JPEG;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return PNG;
        return OTHER_IMAGE; // ImageIO + TwelveMonkeys will try (WebP, BMP, GIF, PSD...)
    }

    private static boolean starts(byte[] b, String p) {
        if (b.length < p.length()) return false;
        for (int i = 0; i < p.length(); i++) if (b[i] != p.charAt(i)) return false;
        return true;
    }
}
