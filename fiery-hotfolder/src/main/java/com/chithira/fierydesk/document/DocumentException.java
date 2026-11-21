package com.chithira.fierydesk.document;

/** The file can't be turned into a print job (unsupported, unreadable or too low resolution). */
public class DocumentException extends RuntimeException {
    public DocumentException(String message) { super(message); }
}
