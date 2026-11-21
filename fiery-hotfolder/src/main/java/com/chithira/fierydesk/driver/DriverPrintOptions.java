package com.chithira.fierydesk.driver;

import com.chithira.fierydesk.core.Sizing;

import javax.print.attribute.standard.MediaSizeName;

/**
 * Settings for printing through the installed printer driver (like Ctrl+P).
 *
 * @param printerName      Windows printer, e.g. the Fiery "Hold" queue printer
 * @param paper            paper size from the driver's list; null = driver default
 * @param sizing           fit / fill / actual size
 * @param copies           copies of each file
 * @param combineIntoOneJob true = all files as pages of one job, false = one job per file
 * @param showPrintDialog  show the normal Windows print dialog once for the batch
 *                         (gives access to the Fiery driver's Properties: media type, tray, finishing…)
 */
public record DriverPrintOptions(String printerName, MediaSizeName paper, Sizing sizing, int copies,
                                 boolean combineIntoOneJob, boolean showPrintDialog) {}
