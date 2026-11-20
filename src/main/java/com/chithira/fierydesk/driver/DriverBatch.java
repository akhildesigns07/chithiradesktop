package com.chithira.fierydesk.driver;

import com.chithira.fierydesk.core.SheetJob;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Files staff ticked to print through the driver, with the options chosen once for all of them.
 *
 * @param sheets imposed and/or front/back jobs, keyed by their front file; these are made into a
 *               sheet PDF first and printed two-sided when they have a back
 */
public record DriverBatch(List<Path> files, DriverPrintOptions options, Map<Path, SheetJob> sheets) {

    public DriverBatch(List<Path> files, DriverPrintOptions options) {
        this(files, options, Map.of());
    }
}
