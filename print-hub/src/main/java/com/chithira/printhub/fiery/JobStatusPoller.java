package com.chithira.printhub.fiery;

import com.chithira.printhub.jobs.PrintJob;
import com.chithira.printhub.jobs.PrintJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every {@code printhub.fiery.poll-seconds} (default 60): asks the Fiery for each job still on it
 * (SENT, HELD, PRINTING, PRINTED) and moves the job on. When the Fiery no longer knows a job
 * ({@code missing-checks-before-removed} times in a row), the job was deleted there and now waits for
 * the print room's printed and waste counts.
 */
@Component
public class JobStatusPoller {

    private static final Logger log = LoggerFactory.getLogger(JobStatusPoller.class);

    private final FieryProperties properties;
    private final FieryApiClient fiery;
    private final FieryStatusMapper mapper;
    private final PrintJobService jobs;
    private final PollerHealth health;
    /** Consecutive "not found" answers per hub job id. */
    private final Map<Long, Integer> missingCounts = new ConcurrentHashMap<>();

    public JobStatusPoller(FieryProperties properties, FieryApiClient fiery, FieryStatusMapper mapper,
                           PrintJobService jobs, PollerHealth health) {
        this.properties = properties;
        this.fiery = fiery;
        this.mapper = mapper;
        this.jobs = jobs;
        this.health = health;
    }

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "#{${printhub.fiery.poll-seconds:60} * 1000}")
    public void poll() {
        if (!properties.isConfigured()) return;
        List<PrintJob> open = jobs.jobsOnFiery();
        int changes = 0;
        try {
            for (PrintJob job : open) {
                if (checkJob(job)) changes++;
            }
            health.succeeded(open.size(), changes);
        } catch (IOException e) {
            health.failed(e.getMessage());
            log.warn("Fiery status poll failed: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            health.failed(e.toString());
            log.error("Fiery status poll failed", e);
        }
    }

    /** @return true if the job's status changed */
    boolean checkJob(PrintJob job) throws IOException, InterruptedException {
        FieryApiClient.JobLookup lookup = fiery.getJob(job.getFieryJobId());
        if (!lookup.found()) {
            int misses = missingCounts.merge(job.getId(), 1, Integer::sum);
            if (misses < properties.getMissingChecksBeforeRemoved()) return false;
            missingCounts.remove(job.getId());
            return jobs.markRemovedFromFiery(job.getId());
        }
        missingCounts.remove(job.getId());
        Optional<String> rawState = mapper.rawState(lookup.body());
        if (rawState.isEmpty()) return false;
        return jobs.applyFieryState(job.getId(), rawState.get(), mapper.map(rawState.get()).orElse(null));
    }
}
