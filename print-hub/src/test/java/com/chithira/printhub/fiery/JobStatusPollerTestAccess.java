package com.chithira.printhub.fiery;

import com.chithira.printhub.jobs.PrintJob;
import org.springframework.boot.test.context.TestComponent;

import java.io.IOException;

/** Lets tests call the poller's per-job check (package-private) directly. */
@TestComponent
public class JobStatusPollerTestAccess {

    private final JobStatusPoller poller;

    public JobStatusPollerTestAccess(JobStatusPoller poller) { this.poller = poller; }

    public boolean check(PrintJob job) throws IOException, InterruptedException { return poller.checkJob(job); }
}
