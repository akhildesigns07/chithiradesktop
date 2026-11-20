package com.chithira.fierydesk.fiery;

/** The Fiery answered, but not with success. */
public class FieryException extends RuntimeException {
    private final int status;
    private final String responseBody;

    public FieryException(String message, int status, String responseBody) {
        super(message + " (HTTP " + status + ")" + (responseBody == null || responseBody.isBlank() ? "" : ": " + responseBody));
        this.status = status;
        this.responseBody = responseBody;
    }

    public int status() { return status; }
    public String responseBody() { return responseBody; }
}
