package com.chithira.printhub.dashboard;

import com.chithira.printhub.jobs.PrintJobStatus;
import com.chithira.printhub.jobs.SendMethod;

import java.time.Instant;

/** The few fields of one print job that the dashboards need. */
record JobFact(long jobId, Instant sentAt, PrintJobStatus status, SendMethod sendMethod, int sheetsRequested,
               Integer printedCount, Integer wasteCount, String wasteReason,
               Long customerId, String customerCode, String customerName,
               String paperPcmid, String paperName, String deviceName, String operator, String fileName) {

    boolean counted() { return status == PrintJobStatus.COUNTED && printedCount != null; }

    /** Reached the Fiery or the printer (not rejected, not cancelled). */
    boolean sent() { return status != PrintJobStatus.FAILED && status != PrintJobStatus.CANCELLED; }

    int printed() { return counted() ? printedCount : 0; }

    int waste() { return counted() && wasteCount != null ? wasteCount : 0; }
}
