package com.chithira.fierydesk.core;

import com.chithira.fierydesk.fiery.Paper;

import java.nio.file.Path;

/**
 * What staff chose for one file. The queue is always "hold".
 *
 * @param converter for the DRIVER_API method: the printer whose driver converts the file to
 *                  PostScript (or PostScriptConverter.BUILT_IN); null for the plain API method
 */
public record JobRequest(Path file, String jobName, Paper paper, Sizing sizing, int copies,
                         String instructions, boolean allowLowRes, String converter) {

    public JobRequest(Path file, String jobName, Paper paper, Sizing sizing, int copies,
                      String instructions, boolean allowLowRes) {
        this(file, jobName, paper, sizing, copies, instructions, allowLowRes, null);
    }
}
