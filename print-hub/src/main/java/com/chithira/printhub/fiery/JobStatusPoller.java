package com.chithira.printhub.fiery;

import com.chithira.printhub.common.NotFoundException;
import com.chithira.printhub.common.events.JobSent;
import com.chithira.printhub.jobs.PrintJob;
import com.chithira.printhub.jobs.PrintJobService;
import com.chithira.printhub.jobs.PrintJobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Follows every job still on the Fiery (SENT, HELD, PRINTING, PRINTED) and moves it on:
 * <ul>
 *   <li>every {@code printhub.fiery.poll-seconds} (default 10) for all open jobs;</li>
 *   <li>about {@link #NEW_JOB_DELAY} after a desktop reports a job, so "Held" shows almost at once;</li>
 *   <li>{@link #RECHECK_DELAY} after a job is first not found, to confirm the delete quickly;</li>
 *   <li>on demand ("Check now" on the job page).</li>
 * </ul>
 * A job counts as deleted on the Fiery only after {@code missing-checks-before-removed} "not found"
 * answers in a row, so one network hiccup never closes it. It then waits for the print room's counts.
 */
@Component
public class JobStatusPoller {

    /** What one check found, for the "Check now" message. */
    public record CheckResult(boolean changed, String message) {}

    static final Duration NEW_JOB_DELAY = Duration.ofSeconds(3);
    static final Duration RECHECK_DELAY = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(JobStatusPoller.class);

    private final FieryProperties properties;
    private final FieryApiClient fiery;
    private final FieryStatusMapper mapper;
    private final PrintJobService jobs;
    private final PollerHealth health;
    private final TaskScheduler scheduler;
    /** Consecutive "not found" answers per hub job id. */
    private final Map<Long, Integer> missingCounts = new ConcurrentHashMap<>();
    /** Jobs being checked right now (the minute poll and a quick check must not overlap on one job). */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public JobStatusPoller(FieryProperties properties, FieryApiClient fiery, FieryStatusMapper mapper,
                           PrintJobService jobs, PollerHealth health, TaskScheduler scheduler) {
        this.properties = properties;
        this.fiery = fiery;
        this.mapper = mapper;
        this.jobs = jobs;
        this.health = health;
        this.scheduler = scheduler;
    }

    /** Regular check of every open job. */
    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "#{${printhub.fiery.poll-seconds:10} * 1000}")
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

    /** A desktop just reported a job: look at it on the Fiery shortly, after the report is saved. */
    @TransactionalEventListener
    public void onJobSent(JobSent event) {
        if (!properties.isConfigured() || !PrintJobStatus.SENT.name().equals(event.status())) return;
        scheduleCheck(event.jobId(), NEW_JOB_DELAY);
    }

    /** "Check now" on the job page: asks the Fiery about this one job immediately. */
    public CheckResult checkNow(long jobId) {
        if (!properties.isConfigured()) return new CheckResult(false, "Fiery status tracking is off (printhub.fiery.enabled)");
        PrintJob job = jobs.get(jobId);
        if (!job.getStatus().isOnFiery() || job.getFieryJobId() == null) {
            return new CheckResult(false, "This job is no longer followed on the Fiery (" + job.getStatus().label() + ")");
        }
        try {
            boolean changed = checkJob(job);
            PrintJob now = jobs.get(jobId);
            String fieryState = now.getFieryStateRaw() == null ? "no state yet" : now.getFieryStateRaw();
            if (missingCounts.containsKey(jobId)) {
                return new CheckResult(false, "Fiery doesn't have job " + job.getFieryJobId() + " – checking again in "
                        + RECHECK_DELAY.toSeconds() + " s to confirm it was deleted");
            }
            return new CheckResult(changed, "Fiery says: " + fieryState + " · status " + now.getStatus().label()
                    + (changed ? " (updated)" : " (unchanged)"));
        } catch (IOException e) {
            health.failed(e.getMessage());
            return new CheckResult(false, "Fiery not reachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CheckResult(false, "Interrupted");
        }
    }

    /** @return true if the job's status changed */
    boolean checkJob(PrintJob job) throws IOException, InterruptedException {
        if (!inFlight.add(job.getId())) return false;          // already being checked
        try {
            FieryApiClient.JobLookup lookup = fiery.getJob(job.getFieryJobId());
            if (!lookup.found()) return handleMissing(job.getId());
            missingCounts.remove(job.getId());
            Optional<String> rawState = mapper.rawState(lookup.body());
            if (rawState.isEmpty()) return false;
            return jobs.applyFieryState(job.getId(), rawState.get(), mapper.map(rawState.get()).orElse(null));
        } finally {
            inFlight.remove(job.getId());
        }
    }

    /** Not found on the Fiery: removed after enough misses in a row; otherwise re-checked soon. */
    private boolean handleMissing(long jobId) {
        int misses = missingCounts.merge(jobId, 1, Integer::sum);
        if (misses < properties.getMissingChecksBeforeRemoved()) {
            scheduleCheck(jobId, RECHECK_DELAY);
            return false;
        }
        missingCounts.remove(jobId);
        return jobs.markRemovedFromFiery(jobId);
    }

    private void scheduleCheck(long jobId, Duration delay) {
        if (!properties.isConfigured()) return;
        scheduler.schedule(() -> quickCheck(jobId), Instant.now().plus(delay));
    }

    private void quickCheck(long jobId) {
        try {
            PrintJob job = jobs.get(jobId);
            if (job.getStatus().isOnFiery() && job.getFieryJobId() != null) checkJob(job);
        } catch (NotFoundException e) {
            // job gone (should not happen); nothing to do
        } catch (IOException e) {
            health.failed(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Quick Fiery check of job {} failed: {}", jobId, e.toString());
        }
    }
}
