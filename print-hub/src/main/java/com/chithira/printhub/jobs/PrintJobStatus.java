package com.chithira.printhub.jobs;

/**
 * Life cycle of a print job:
 * <pre>
 * SENT → HELD → PRINTING → PRINTED → REMOVED → AWAITING_COUNTS → COUNTED
 *   any open status → CANCELLED;  a send the Fiery rejected → FAILED
 * </pre>
 * Driver-method jobs have no Fiery job id to follow, so they start at AWAITING_COUNTS.
 */
public enum PrintJobStatus {
    SENT, HELD, PRINTING, PRINTED, REMOVED, AWAITING_COUNTS, COUNTED, CANCELLED, FAILED;

    /** True while the job is still on (or on its way to) the Fiery. */
    public boolean isOnFiery() { return this == SENT || this == HELD || this == PRINTING || this == PRINTED; }
}
