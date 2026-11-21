package com.chithira.printhub.jobs;

/** How the desktop sent the job (same names as the desktop's {@code send.method}). */
public enum SendMethod {
    /** Fiery API to the Hold queue. */
    API,
    /** Converted by the Fiery driver to PostScript, then uploaded with the API. */
    DRIVER_API,
    /** Printed through the Windows printer driver; no Fiery job id. */
    DRIVER;

    public boolean hasFieryJobId() { return this != DRIVER; }
}
