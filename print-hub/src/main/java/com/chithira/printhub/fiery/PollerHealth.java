package com.chithira.printhub.fiery;

import org.springframework.stereotype.Component;

import java.time.Instant;

/** What the last Fiery poll did, shown on the job and print-room pages. */
@Component
public class PollerHealth {

    private volatile Instant lastRunAt;
    private volatile Instant lastSuccessAt;
    private volatile String lastError;
    private volatile int lastJobsChecked;
    private volatile int lastChanges;

    void succeeded(int jobsChecked, int changes) {
        lastRunAt = lastSuccessAt = Instant.now();
        lastJobsChecked = jobsChecked;
        lastChanges = changes;
        lastError = null;
    }

    void failed(String error) {
        lastRunAt = Instant.now();
        lastError = error;
    }

    public Instant getLastRunAt() { return lastRunAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public String getLastError() { return lastError; }
    public int getLastJobsChecked() { return lastJobsChecked; }
    public int getLastChanges() { return lastChanges; }
    public boolean isHealthy() { return lastRunAt != null && lastError == null; }
}
