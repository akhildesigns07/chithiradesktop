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

    /** Printed and waste counts can be entered (or corrected) in these statuses. */
    public boolean acceptsCounts() {
        return this == PRINTED || this == REMOVED || this == AWAITING_COUNTS || this == COUNTED;
    }

    /** No further changes except count corrections. */
    public boolean isClosed() { return this == COUNTED || this == CANCELLED || this == FAILED; }

    /** Bootstrap badge colour for the pages. */
    public String badge() {
        return switch (this) {
            case SENT, HELD -> "text-bg-secondary";
            case PRINTING -> "text-bg-info";
            case PRINTED, REMOVED -> "text-bg-primary";
            case AWAITING_COUNTS -> "text-bg-warning";
            case COUNTED -> "text-bg-success";
            case CANCELLED -> "text-bg-light";
            case FAILED -> "text-bg-danger";
        };
    }

    /** Wording for staff, e.g. "Awaiting counts". */
    public String label() {
        String words = name().replace('_', ' ').toLowerCase();
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
