package com.chithira.printhub.common.events;

/** Published when a job moves to another status (poller, print room or staff). */
public record JobStatusChanged(long jobId, String fromStatus, String toStatus, String source) {}
