package com.chithira.fierydesk.document;

import java.util.List;

/** A print-ready file. effectiveDpi is null when the source was already PDF/PS/TIFF. */
public record PreparedDocument(byte[] content, String filename, String contentType,
                               FileKind sourceKind, Integer effectiveDpi, List<String> warnings) {}
