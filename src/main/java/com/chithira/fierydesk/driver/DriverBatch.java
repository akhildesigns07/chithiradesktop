package com.chithira.fierydesk.driver;

import java.nio.file.Path;
import java.util.List;

/** Files staff ticked to print through the driver, with the options chosen once for all of them. */
public record DriverBatch(List<Path> files, DriverPrintOptions options) {}
