package com.chithira.fierydesk.core;

import com.chithira.fierydesk.fiery.Paper;

import java.nio.file.Path;

/**
 * What staff chose for one file (or front/back pair). The queue is always "hold".
 *
 * @param converter for the DRIVER_API method: the printer whose driver converts the file to
 *                  PostScript (or PostScriptConverter.BUILT_IN); null for the plain API method
 * @param sheet     imposition and/or front + back: the file is first made into a PDF sheet; null otherwise
 * @param customer  Print Hub customer the job is for; null when the hub is off or no customer was chosen
 */
public record JobRequest(Path file, String jobName, Paper paper, Sizing sizing, int copies,
                         String instructions, boolean allowLowRes, String converter, SheetJob sheet,
                         CustomerRef customer) {

    public JobRequest(Path file, String jobName, Paper paper, Sizing sizing, int copies,
                      String instructions, boolean allowLowRes, String converter, SheetJob sheet) {
        this(file, jobName, paper, sizing, copies, instructions, allowLowRes, converter, sheet, null);
    }

    public JobRequest(Path file, String jobName, Paper paper, Sizing sizing, int copies,
                      String instructions, boolean allowLowRes, String converter) {
        this(file, jobName, paper, sizing, copies, instructions, allowLowRes, converter, null);
    }

    public JobRequest(Path file, String jobName, Paper paper, Sizing sizing, int copies,
                      String instructions, boolean allowLowRes) {
        this(file, jobName, paper, sizing, copies, instructions, allowLowRes, null, null);
    }

    /** The back image of a front/back pair, or null. */
    public Path back() { return sheet == null ? null : sheet.back(); }
}
