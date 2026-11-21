package com.chithira.printhub.common.events;

/**
 * Published when a desktop reports a job (sent or failed). Phase 3 listeners: the status poller
 * starts following it; later: billing and inventory.
 *
 * @param customerId null when the job has no customer
 * @param status     the job's first status (SENT, AWAITING_COUNTS or FAILED)
 */
public record JobSent(long jobId, Long customerId, String status) {}
