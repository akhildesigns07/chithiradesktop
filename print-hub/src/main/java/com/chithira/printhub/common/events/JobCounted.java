package com.chithira.printhub.common.events;

/**
 * Published when the print room enters or corrects a job's counts. Planned listeners: billing
 * (bill lines), inventory (paper stock: printed + waste sheets).
 *
 * @param previousPrinted / previousWaste  null the first time
 */
public record JobCounted(long jobId, Long customerId, String paperPcmid, int printed, int waste,
                         Integer previousPrinted, Integer previousWaste) {}
