package com.chithira.printhub.jobs;

import com.chithira.printhub.audit.AuditService;
import com.chithira.printhub.common.BusinessException;
import com.chithira.printhub.common.NotFoundException;
import com.chithira.printhub.common.events.JobCounted;
import com.chithira.printhub.common.events.JobSent;
import com.chithira.printhub.common.events.JobStatusChanged;
import com.chithira.printhub.customers.Customer;
import com.chithira.printhub.customers.CustomerService;
import com.chithira.printhub.devices.Device;
import com.chithira.printhub.devices.DeviceService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.chithira.printhub.jobs.PrintJobStatus.*;

/**
 * Print job rules: saving what the desktops report, following the Fiery (via the poller), the print room's
 * printed and waste counts, and cancelling. Every change gets a status-history row (when the status changes),
 * an audit row and a domain event.
 */
@Service
public class PrintJobService {

    /** A saved job, and whether it was already known (a retry from the desktop outbox). */
    public record Recorded(PrintJob job, boolean duplicate) {}

    /** A customer's counted totals. */
    public record Totals(long jobs, long printed, long waste) {
        public double wastePercent() { return printed + waste == 0 ? 0 : 100.0 * waste / (printed + waste); }
    }

    private static final Set<PrintJobStatus> ON_FIERY = EnumSet.of(SENT, HELD, PRINTING, PRINTED);
    private static final Set<PrintJobStatus> BEING_PRINTED = EnumSet.of(SENT, HELD, PRINTING);
    private static final Set<PrintJobStatus> WAITING_FOR_COUNTS = EnumSet.of(PRINTED, REMOVED, AWAITING_COUNTS);

    private final PrintJobRepository jobs;
    private final JobStatusHistoryRepository history;
    private final CustomerService customers;
    private final DeviceService devices;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final PrintRoomProperties printRoom;

    public PrintJobService(PrintJobRepository jobs, JobStatusHistoryRepository history, CustomerService customers,
                           DeviceService devices, AuditService audit, ApplicationEventPublisher events,
                           PrintRoomProperties printRoom) {
        this.jobs = jobs;
        this.history = history;
        this.customers = customers;
        this.devices = devices;
        this.audit = audit;
        this.events = events;
        this.printRoom = printRoom;
    }

    // ================================================================== reading

    public PrintJob get(long id) { return jobs.findWithDetailsById(id).orElseThrow(() -> new NotFoundException("Print job")); }

    public Page<PrintJob> search(JobSearch search, int page) {
        return jobs.findAll(search.toSpecification(),
                PageRequest.of(Math.max(0, page), 50, Sort.by(Sort.Direction.DESC, "sentAt", "id")));
    }

    public List<JobStatusHistory> history(long jobId) { return history.findByJobIdOrderById(jobId); }

    /** Jobs the poller follows on the Fiery. */
    public List<PrintJob> jobsOnFiery() { return jobs.findByStatusInAndFieryJobIdIsNotNullOrderById(ON_FIERY); }

    /** Print room: jobs still in the Hold queue or printing. */
    public List<PrintJob> beingPrinted() { return jobs.findByStatusInOrderBySentAt(BEING_PRINTED); }

    /** Print room: printed (or deleted from the Fiery) and waiting for printed / waste counts, oldest first. */
    public List<PrintJob> waitingForCounts() { return jobs.findByStatusInOrderBySentAt(WAITING_FOR_COUNTS); }

    /** Print room: counted in the last {@code hours}, newest first (to correct mistakes). */
    public List<PrintJob> countedRecently(int hours) {
        return jobs.findByStatusAndCountedAtAfterOrderByCountedAtDesc(COUNTED, Instant.now().minus(Duration.ofHours(hours)));
    }

    public List<PrintJob> recentForCustomer(long customerId) { return jobs.findTop20ByCustomerIdOrderBySentAtDesc(customerId); }

    public long jobCountForCustomer(long customerId) { return jobs.countByCustomerId(customerId); }

    public Totals totalsForCustomer(long customerId) {
        Object[] row = jobs.countedTotalsForCustomer(customerId);
        return new Totals(((Number) row[0]).longValue(), ((Number) row[1]).longValue(), ((Number) row[2]).longValue());
    }

    /** True when a job has waited longer than {@code printhub.print-room.counts-overdue-hours} for its counts. */
    public boolean isCountsOverdue(PrintJob job) {
        if (!WAITING_FOR_COUNTS.contains(job.getStatus())) return false;
        Instant since = job.getRemovedAt() != null ? job.getRemovedAt()
                : job.getPrintedAt() != null ? job.getPrintedAt() : job.getSentAt();
        return Duration.between(since, Instant.now()).toHours() >= printRoom.countsOverdueHours();
    }

    public List<String> wasteReasons() { return printRoom.wasteReasons(); }

    /**
     * What open pages poll every few seconds: the status of the jobs they show, how many jobs the print
     * room has open, and the newest job id.
     */
    public LiveStatus liveStatus(Collection<Long> jobIds) {
        Map<String, String> statuses = new LinkedHashMap<>();
        if (!jobIds.isEmpty()) {
            jobs.findAllById(jobIds).forEach(job -> statuses.put(String.valueOf(job.getId()), job.getStatus().name()));
        }
        Set<PrintJobStatus> open = EnumSet.copyOf(BEING_PRINTED);
        open.addAll(WAITING_FOR_COUNTS);
        return new LiveStatus(statuses, jobs.countByStatusIn(open), jobs.latestId());
    }

    /** See {@link #liveStatus}. */
    public record LiveStatus(Map<String, String> statuses, long openJobs, long latestJobId) {}

    public long latestJobId() { return jobs.latestId(); }

    /** "12:HELD,13:SENT" for a page's {@code data-statuses} (read by live-status.js). */
    public static String encodeStatuses(Collection<PrintJob> shown) {
        StringBuilder text = new StringBuilder();
        for (PrintJob job : shown) {
            if (!text.isEmpty()) text.append(',');
            text.append(job.getId()).append(':').append(job.getStatus().name());
        }
        return text.toString();
    }

    // ================================================================== from the desktops

    /**
     * Saves a job reported by a desktop. Safe to call again with the same {@code clientJobUuid}:
     * the job saved the first time is returned unchanged.
     */
    @Transactional
    public Recorded recordFromDesktop(long deviceId, JobReport report) {
        Optional<PrintJob> existing = jobs.findByDeviceIdAndClientJobUuid(deviceId, report.clientJobUuid().toLowerCase());
        if (existing.isPresent()) return new Recorded(existing.get(), true);

        if (!report.failed() && report.sendMethod().hasFieryJobId() && isBlank(report.fieryJobId())) {
            throw new BusinessException("fieryJobId is required for a job sent with the Fiery API");
        }
        Device device = devices.get(deviceId);
        Customer customer = report.customerId() == null ? null : findCustomer(report.customerId());

        PrintJob job = new PrintJob();
        job.setClientJobUuid(report.clientJobUuid().toLowerCase());
        job.setDevice(device);
        job.setOperator(trim(report.operator()));
        job.setCustomer(customer);
        job.setFieryJobId(trim(report.fieryJobId()));
        job.setSendMethod(report.sendMethod());
        job.setFileName(report.fileName().trim());
        job.setBackFileName(trim(report.backFileName()));
        job.setJobName(trim(report.jobName()));
        job.setPaperPcmid(trim(report.paperId()));
        job.setPaperName(trim(report.paperName()));
        if (report.fileWidthMm() != null && report.fileHeightMm() != null) {
            job.setFileSize(report.fileWidthMm(), report.fileHeightMm(), trim(report.fileSizeSource()));
        }
        job.setImposed(report.imposed());
        job.setQuantityPieces(report.quantity());
        job.setPiecesPerSheet(report.piecesPerSheet());
        job.setSheetsRequested(report.sheets());
        job.setDuplex(report.duplex());
        job.setInstructions(trim(report.instructions()));
        job.setSentAt(report.sentAt());
        job.setErrorMessage(trim(report.error()));
        job.setStatus(initialStatus(report));
        jobs.save(job);

        history.save(new JobStatusHistory(job.getId(), null, job.getStatus(), JobStatusHistory.Source.DESKTOP,
                report.failed() ? "Rejected: " + report.error() : "Reported by " + device.getName()));
        String who = customer == null ? "no customer" : customer.getCode() + " " + customer.getName();
        audit.record(report.failed() ? "JOB_FAILED" : "JOB_SENT", "PRINT_JOB", job.getId(),
                job.getFileName() + " (" + who + ")" + (job.getFieryJobId() == null ? "" : ", Fiery job " + job.getFieryJobId()),
                null, job.toAudit());
        events.publishEvent(new JobSent(job.getId(), customer == null ? null : customer.getId(), job.getStatus().name()));
        return new Recorded(job, false);
    }

    /**
     * Where a new job starts: FAILED if rejected; AWAITING_COUNTS for driver jobs (nothing to follow
     * on the Fiery); otherwise SENT until the poller sees it in the Hold queue.
     */
    static PrintJobStatus initialStatus(JobReport report) {
        if (report.failed()) return FAILED;
        if (!report.sendMethod().hasFieryJobId()) return AWAITING_COUNTS;
        return SENT;
    }

    // ================================================================== from the Fiery (poller)

    /**
     * The Fiery reports a state for the job. Moves forward only (Sent → Held → Printing → Printed), except
     * Printing → Held when a print is stopped. Unknown states ({@code mapped} null) are only remembered.
     *
     * @return true if the status changed
     */
    @Transactional
    public boolean applyFieryState(long jobId, String rawState, PrintJobStatus mapped) {
        PrintJob job = get(jobId);
        if (!job.getStatus().isOnFiery()) return false;
        if (!Objects.equals(rawState, job.getFieryStateRaw())) job.setFieryStateRaw(rawState);
        if (mapped == null || mapped == job.getStatus() || !ON_FIERY.contains(mapped)) return false;
        boolean forward = mapped.ordinal() > job.getStatus().ordinal();
        boolean stopped = job.getStatus() == PRINTING && mapped == HELD;
        if (!forward && !stopped) return false;
        if (mapped == PRINTED) job.setPrintedAt(Instant.now());
        changeStatus(job, mapped, JobStatusHistory.Source.POLLER, "Fiery state: " + rawState);
        return true;
    }

    /**
     * The Fiery no longer has the job: staff deleted it after printing. It now waits for the print room's counts.
     *
     * @return true if the status changed
     */
    @Transactional
    public boolean markRemovedFromFiery(long jobId) {
        PrintJob job = get(jobId);
        if (!job.getStatus().isOnFiery()) return false;
        job.setRemovedAt(Instant.now());
        changeStatus(job, REMOVED, JobStatusHistory.Source.POLLER, "No longer in the Fiery queue");
        changeStatus(job, AWAITING_COUNTS, JobStatusHistory.Source.POLLER, "Waiting for printed and waste counts");
        return true;
    }

    // ================================================================== print room and staff

    /**
     * Enters (or corrects) how many sheets printed well and how many were wasted.
     * Allowed once the job has printed: Printed, Removed, Awaiting counts, or Counted (a correction).
     */
    @Transactional
    public PrintJob enterCounts(long jobId, JobCounts counts) {
        PrintJob job = get(jobId);
        if (!job.getStatus().acceptsCounts()) {
            throw new BusinessException("Counts can be entered once the job has printed (now: " + job.getStatus().label() + ")");
        }
        int printed = counts.getPrinted(), waste = counts.getWaste() == null ? 0 : counts.getWaste();
        String reason = trim(counts.getWasteReason());
        if (waste > 0 && reason == null) throw new BusinessException("Choose why sheets were wasted");
        if (waste == 0) reason = null;

        var before = job.toAudit();
        Integer previousPrinted = job.getPrintedCount(), previousWaste = job.getWasteCount();
        boolean correction = job.getStatus() == COUNTED;
        job.setCounts(printed, waste, reason, audit.current().name());
        if (!correction) changeStatus(job, COUNTED, JobStatusHistory.Source.USER, "Printed " + printed + ", waste " + waste);
        audit.record(correction ? "JOB_COUNTS_CORRECTED" : "JOB_COUNTED", "PRINT_JOB", jobId,
                job.getFileName() + ": printed " + printed + ", waste " + waste + (reason == null ? "" : " (" + reason + ")")
                        + (correction ? " – was printed " + previousPrinted + ", waste " + previousWaste : ""),
                before, job.toAudit());
        events.publishEvent(new JobCounted(jobId, job.getCustomer() == null ? null : job.getCustomer().getId(),
                job.getPaperPcmid(), printed, waste, previousPrinted, previousWaste));
        return job;
    }

    /** Cancels a job that will not be printed (or not counted). */
    @Transactional
    public void cancel(long jobId, String reason) {
        PrintJob job = get(jobId);
        if (job.getStatus().isClosed()) throw new BusinessException("This job is already " + job.getStatus().label().toLowerCase());
        String why = trim(reason);
        if (why == null) throw new BusinessException("Enter why the job is cancelled");
        var before = job.toAudit();
        changeStatus(job, CANCELLED, JobStatusHistory.Source.USER, why);
        audit.record("JOB_CANCELLED", "PRINT_JOB", jobId, job.getFileName() + " cancelled: " + why, before, job.toAudit());
    }

    // ================================================================== helpers

    private void changeStatus(PrintJob job, PrintJobStatus to, JobStatusHistory.Source source, String note) {
        PrintJobStatus from = job.getStatus();
        job.setStatus(to);
        history.save(new JobStatusHistory(job.getId(), from, to, source, note));
        if (source != JobStatusHistory.Source.USER) {
            audit.record("JOB_STATUS", "PRINT_JOB", job.getId(),
                    job.getFileName() + ": " + from.label() + " → " + to.label() + " (" + note + ")", null, null);
        }
        events.publishEvent(new JobStatusChanged(job.getId(), from.name(), to.name(), source.name()));
    }

    private Customer findCustomer(long customerId) {
        try {
            return customers.get(customerId);
        } catch (NotFoundException e) {
            throw new BusinessException("Unknown customerId " + customerId);
        }
    }

    private static boolean isBlank(String text) { return text == null || text.isBlank(); }

    private static String trim(String text) { return isBlank(text) ? null : text.trim(); }
}
